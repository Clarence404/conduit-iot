package dev.conduit.e2e;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 测试机上的一个 java -jar 进程（server 或 transport）：可启动、强制结束（模拟崩溃）、正常停止、再启动，并对其 HTTP 端口发 GET。
 * 标准输出与错误追加到工作目录下的 console.log；环境变量在继承的基础上覆盖，并去掉 SPRING_PROFILES_ACTIVE。
 */
final class AppProcess {

    private static final Logger log = LoggerFactory.getLogger(AppProcess.class);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private final String name;
    private final Path jar;
    private final Map<String, String> env;
    private final int httpPort;
    private final Path workDir;
    private Process process;
    private Thread shutdownHook;

    AppProcess(String name, Path jar, Map<String, String> env, int httpPort, Path workDir) {
        this.name = name;
        this.jar = jar;
        this.env = Map.copyOf(env);
        this.httpPort = httpPort;
        this.workDir = workDir;
    }

    String name() {
        return name;
    }

    Path consoleLog() {
        return workDir.resolve("console.log");
    }

    /** 启动进程（已在运行时什么也不做）；不等待就绪，由调用方轮询健康检查。 */
    synchronized void start() throws IOException {
        if (alive()) {
            return;
        }
        Files.createDirectories(workDir);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>(List.of(java, "-Xmx512m", "-jar", jar.toString(),
                "--server.port=" + httpPort));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(consoleLog().toFile()));
        // 子进程默认继承调用者环境：清掉可能来自开发者 shell 的配置，避免泄漏进 e2e 进程（PATH、JAVA_HOME 等保留）
        builder.environment().remove("SPRING_PROFILES_ACTIVE");
        builder.environment().remove("TRANSPORT_CLIENT_ID");
        builder.environment().keySet().removeIf(k -> k.startsWith("TRANSPORT_MQTT_") || k.startsWith("CONDUIT_")
                || k.startsWith("POSTGRES_") || k.startsWith("REDIS_") || k.startsWith("INFLUX_")
                || k.startsWith("EMQX_") || k.startsWith("PIPELINE_"));
        builder.environment().putAll(env);
        process = builder.start();
        // 测试 JVM 被强行终止时也要带走子进程，避免遗留孤儿 java 进程；kill() 会撤销该钩子
        Process started = process;
        shutdownHook = new Thread(() -> {
            if (started.isAlive()) {
                started.destroyForcibly();
            }
        }, "kill-" + name);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        log.info("Started {} (pid {}) on port {}", name, process.pid(), httpPort);
    }

    /** 强制结束（不执行关闭钩子，不释放身份租约、不发 MQTT DISCONNECT），最多等 30 秒退出。 */
    synchronized void kill() {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroyForcibly();
        try {
            process.waitFor(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (process.isAlive()) {
            log.warn("{} (pid {}) is still alive 30 seconds after destroyForcibly", name, process.pid());
        } else {
            removeShutdownHook();
            log.info("Killed {} (pid {})", name, process.pid());
        }
    }

    /**
     * 正常停止：请求正常终止（Linux 上为 SIGTERM，应用执行关闭流程，如 transport 释放身份租约），最多等 40 秒，
     * 仍未退出时强制结束。不支持正常终止的平台（Windows 上 destroy 即强制结束）记 WARN，效果等同 {@link #kill()}。
     */
    synchronized void stop() {
        if (process == null || !process.isAlive()) {
            return;
        }
        if (!process.supportsNormalTermination()) {
            log.warn("{} cannot be terminated normally on this platform, killing it instead", name);
        }
        process.destroy();
        try {
            if (!process.waitFor(40, TimeUnit.SECONDS)) {
                log.warn("{} (pid {}) did not stop within 40 seconds, killing it", name, process.pid());
                process.destroyForcibly();
                process.waitFor(30, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!process.isAlive()) {
            removeShutdownHook();
            log.info("Stopped {} (pid {})", name, process.pid());
        }
    }

    /** 进程已结束时撤销关闭钩子（JVM 正在关闭时撤销会抛异常，忽略）。 */
    private void removeShutdownHook() {
        if (shutdownHook == null) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            log.trace("JVM is shutting down, hook of {} stays", name);
        }
        shutdownHook = null;
    }

    synchronized boolean alive() {
        return process != null && process.isAlive();
    }

    /**
     * GET http://localhost:{port}{path}。
     *
     * @return 响应体（任何状态码）；连接失败、超时或进程未运行时为 null
     */
    String get(String path) {
        if (!alive()) {
            return null;
        }
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + httpPort + path)).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.body();
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
