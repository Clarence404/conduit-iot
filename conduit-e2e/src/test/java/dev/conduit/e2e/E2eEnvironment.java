package dev.conduit.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import dev.conduit.simulator.ConduitApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;

/**
 * e2e 的独立临时环境：Testcontainers 在 Docker 宿主机上起 PostgreSQL / Redis / InfluxDB / EMQX（磁盘持久会话、单节点，
 * 鉴权回调指向 E2E_DEV_HOST 上的 server），测试机本地以 jar 启动 1 个 server 与 2 个 transport 连接它们。
 * 提供故障注入（强制结束进程、重启 EMQX 容器）与检查（InfluxDB 计数、死信、待确认数）。
 * 口令与 token 每次运行随机生成，只存在于内存与子进程环境变量中；结束时进程与容器全部清理。
 */
final class E2eEnvironment implements AutoCloseable {

    static final String ADMIN_USERNAME = "admin";
    static final Duration CLAIM_MIN_IDLE = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(E2eEnvironment.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int EMQX_MQTT_PORT = 1883;

    private final Path root = root();
    private final Path logDir = root.resolve("conduit-e2e/target/e2e-logs")
            .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
    private final String devHost;
    private final String postgresPassword = hex(16);
    private final String influxToken = "apiv3_" + hex(24);
    private final String adminPassword = "E2e-" + hex(12) + "-9";
    private final String jwtSecret = hex(32);
    private final String internalToken = hex(24);
    private final String transportPassword = hex(24);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<GenericContainer<?>> containers = new ArrayList<>();
    private final List<AppProcess> transports = new ArrayList<>();

    private GenericContainer<?> postgres;
    private GenericContainer<?> redis;
    private GenericContainer<?> influx;
    private GenericContainer<?> emqx;
    private int emqxPort;
    private int serverPort;
    private AppProcess server;

    private E2eEnvironment(String devHost) {
        this.devHost = devHost;
    }

    /** 仓库根目录：system property conduit.root（由 surefire / failsafe 设置），缺省为上一级目录。 */
    static Path root() {
        return Path.of(System.getProperty("conduit.root", "..")).toAbsolutePath().normalize();
    }

    /**
     * 搭建整套环境并等到某个 transport 成为已连接的主实例。
     *
     * @throws IllegalStateException E2E_DEV_HOST 未设置、jar 未构建、或任一组件未能就绪
     */
    static E2eEnvironment start() throws Exception {
        String devHost = System.getenv("E2E_DEV_HOST");
        if (devHost == null || devHost.isBlank()) {
            throw new IllegalStateException("E2E_DEV_HOST must be set to the address at which the Docker host "
                    + "reaches this machine: EMQX calls the locally running server's /internal/mqtt/auth there");
        }
        E2eEnvironment env = new E2eEnvironment(devHost.trim());
        try {
            env.boot();
            return env;
        } catch (Exception | Error e) {
            env.close();
            throw e;
        }
    }

    private void boot() throws Exception {
        Path serverJar = requireJar("conduit-server/target/conduit-server.jar");
        Path transportJar = requireJar("conduit-transport/target/conduit-transport.jar");
        log.info("e2e logs go to {}", logDir);

        postgres = container(new GenericContainer<>(DockerImageName.parse("postgres:16.8"))
                .withExposedPorts(5432)
                .withEnv("POSTGRES_DB", "conduit")
                .withEnv("POSTGRES_USER", "conduit")
                .withEnv("POSTGRES_PASSWORD", postgresPassword)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2)
                        .withStartupTimeout(Duration.ofMinutes(2))));
        redis = container(new GenericContainer<>(DockerImageName.parse("redis:7.4.2")).withExposedPorts(6379));
        byte[] tokenFile = ("{\"token\":\"" + influxToken + "\",\"name\":\"_admin\"}").getBytes(StandardCharsets.UTF_8);
        influx = container(new GenericContainer<>(DockerImageName.parse("influxdb:3.12.0-core"))
                .withCopyToContainer(Transferable.of(tokenFile, 0644), "/tmp/admin-token.json")
                .withCommand("influxdb3", "serve", "--node-id", "node0", "--object-store", "memory",
                        "--admin-token-file", "/tmp/admin-token.json", "--disable-authz", "health")
                .withExposedPorts(8181)
                .waitingFor(Wait.forHttp("/health").forPort(8181).forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2))));

        serverPort = freePort();
        emqx = startEmqx();

        Map<String, String> serverEnv = new LinkedHashMap<>();
        serverEnv.put("POSTGRES_HOST", postgres.getHost());
        serverEnv.put("POSTGRES_PORT", String.valueOf(postgres.getMappedPort(5432)));
        serverEnv.put("POSTGRES_DB", "conduit");
        serverEnv.put("POSTGRES_USER", "conduit");
        serverEnv.put("POSTGRES_PASSWORD", postgresPassword);
        serverEnv.put("REDIS_HOST", redis.getHost());
        serverEnv.put("REDIS_PORT", String.valueOf(redis.getMappedPort(6379)));
        serverEnv.put("INFLUX_URL", influxUrl());
        serverEnv.put("INFLUX_TOKEN", influxToken);
        serverEnv.put("CONDUIT_ADMIN_USERNAME", ADMIN_USERNAME);
        serverEnv.put("CONDUIT_AUTHORIZATION_MODE", "enforce");
        serverEnv.put("CONDUIT_ADMIN_PASSWORD", adminPassword);
        serverEnv.put("CONDUIT_PLATFORM_ADMIN_PASSWORD", "");
        serverEnv.put("CONDUIT_JWT_SECRET", jwtSecret);
        serverEnv.put("CONDUIT_INTERNAL_TOKEN", internalToken);
        serverEnv.put("TRANSPORT_MQTT_USERNAME", "conduit-transport");
        serverEnv.put("TRANSPORT_MQTT_PASSWORD", transportPassword);
        serverEnv.put("PIPELINE_CLAIM_MIN_IDLE", CLAIM_MIN_IDLE.toSeconds() + "s");
        serverEnv.put("CONDUIT_LOG_DIR", logDir.resolve("server/logs").toString());
        server = new AppProcess("server", serverJar, serverEnv, serverPort, logDir.resolve("server"));
        startServer();

        for (String name : List.of("transport-a", "transport-b")) {
            Map<String, String> transportEnv = new LinkedHashMap<>();
            transportEnv.put("REDIS_HOST", redis.getHost());
            transportEnv.put("REDIS_PORT", String.valueOf(redis.getMappedPort(6379)));
            transportEnv.put("EMQX_HOST", emqx.getHost());
            transportEnv.put("EMQX_PORT", String.valueOf(emqxPort));
            transportEnv.put("TRANSPORT_MQTT_USERNAME", "conduit-transport");
            transportEnv.put("TRANSPORT_MQTT_PASSWORD", transportPassword);
            transportEnv.put("CONDUIT_LOG_DIR", logDir.resolve(name + "/logs").toString());
            AppProcess transport = new AppProcess(name, transportJar, transportEnv, freePort(), logDir.resolve(name));
            transports.add(transport);
            transport.start();
        }
        awaitLeaderConnected(Duration.ofSeconds(90));
    }

    /**
     * 启动 EMQX：配置由部署模板渲染，持久会话与固定节点名同 compose；宿主机端口在 20000～29999 中随机固定
     * （docker restart 后端口不变），被占用时换端口重试，最多 5 次。
     */
    private GenericContainer<?> startEmqx() throws IOException {
        String hocon = EmqxConfig.render(Files.readString(root.resolve("deploy/dev/emqx/base.hocon.tpl")),
                "http://" + devHost + ":" + serverPort, internalToken);
        byte[] acl = Files.readAllBytes(root.resolve("deploy/dev/emqx/acl.conf"));
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            int hostPort = 20000 + ThreadLocalRandom.current().nextInt(10000);
            GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse("emqx/emqx:5.10.5"))
                    .withExposedPorts(EMQX_MQTT_PORT)
                    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                            new PortBinding(Ports.Binding.bindPort(hostPort), new ExposedPort(EMQX_MQTT_PORT))))
                    .withCopyToContainer(Transferable.of(hocon.getBytes(StandardCharsets.UTF_8)),
                            "/opt/emqx/etc/base.hocon")
                    .withCopyToContainer(Transferable.of(acl), "/opt/emqx/etc/conduit-acl.conf")
                    .withEnv("EMQX_DURABLE_SESSIONS__ENABLE", "true")
                    .withEnv("EMQX_FORCE_SHUTDOWN__MAX_HEAP_SIZE", "256MB")
                    .withEnv("EMQX_CLUSTER__DISCOVERY_STRATEGY", "singleton")
                    .withEnv("EMQX_NODE__NAME", "emqx@127.0.0.1")
                    .withEnv("EMQX_DASHBOARD__DEFAULT_PASSWORD", hex(16))
                    .waitingFor(Wait.forLogMessage(".*is running now.*", 1))
                    .withStartupTimeout(Duration.ofMinutes(2));
            try {
                c.start();
                emqxPort = hostPort;
                containers.add(c);
                log.info("EMQX is running on {}:{} with durable sessions", c.getHost(), hostPort);
                return c;
            } catch (RuntimeException e) {
                last = e;
                c.stop();
                log.warn("EMQX did not start on host port {} (attempt {}): {}", hostPort, attempt, e.getMessage());
            }
        }
        throw new IllegalStateException("EMQX did not start after 5 attempts", last);
    }

    private GenericContainer<?> container(GenericContainer<?> c) {
        c.start();
        containers.add(c);
        return c;
    }

    /** 已登录 admin（默认租户的租户管理员）的 REST 客户端；每次新建，避免 15 分钟的令牌过期。 */
    ConduitApiClient api() {
        ConduitApiClient api = new ConduitApiClient(URI.create("http://localhost:" + serverPort));
        api.login(ADMIN_USERNAME, adminPassword);
        return api;
    }

    String mqttHost() {
        return emqx.getHost();
    }

    int mqttPort() {
        return emqxPort;
    }

    /** 启动（或重启）server 并等到 readiness 为 UP，最多 3 分钟；进程提前退出时报错并指明日志。 */
    void startServer() throws IOException {
        server.start();
        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(1)).until(() -> {
            if (!server.alive()) {
                throw new IllegalStateException("server exited during startup, see " + server.consoleLog());
            }
            String body = server.get("/actuator/health/readiness");
            return body != null && body.contains("\"UP\"");
        });
    }

    /** 强制结束 server 进程（模拟崩溃）。 */
    void killServer() {
        server.kill();
    }

    /** 强制结束指定进程（模拟崩溃）。 */
    void kill(AppProcess process) {
        process.kill();
    }

    /** 强制结束全部 transport 进程。 */
    void killTransports() {
        transports.forEach(AppProcess::kill);
    }

    /** 启动全部 transport（已在运行的不受影响），不等待就绪。 */
    void startTransports() throws IOException {
        for (AppProcess transport : transports) {
            transport.start();
        }
    }

    /** 当前的主 transport（健康检查 role 为 LEADER 的进程）。 */
    AppProcess leaderTransport() {
        return findConnectedLeader().orElseThrow(() -> new IllegalStateException("no transport is a connected leader"));
    }

    /** 等到某个 transport 是已连接 EMQX 的主实例，返回它。 */
    AppProcess awaitLeaderConnected(Duration timeout) {
        AtomicReference<AppProcess> leader = new AtomicReference<>();
        await().atMost(timeout).pollInterval(Duration.ofSeconds(1)).until(() -> {
            Optional<AppProcess> found = findConnectedLeader();
            found.ifPresent(leader::set);
            return found.isPresent();
        });
        log.info("{} is the connected leader", leader.get().name());
        return leader.get();
    }

    private Optional<AppProcess> findConnectedLeader() {
        for (AppProcess transport : transports) {
            String body = transport.get("/actuator/health");
            if (body == null) {
                continue;
            }
            try {
                JsonNode details = JSON.readTree(body).path("components").path("leader").path("details");
                if ("LEADER".equals(details.path("role").asText()) && details.path("connected").asBoolean()) {
                    return Optional.of(transport);
                }
            } catch (IOException e) {
                log.debug("Unreadable health of {}: {}", transport.name(), e.getMessage());
            }
        }
        return Optional.empty();
    }

    /** 重启 EMQX 容器（等同 docker restart，容器、数据与固定端口不变），等到 emqx ctl status 成功。 */
    void restartEmqx() {
        log.info("Restarting the EMQX container");
        DockerClientFactory.instance().client().restartContainerCmd(emqx.getContainerId()).withTimeout(10).exec();
        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(2)).until(() -> {
            try {
                ExecResult result = emqx.execInContainer("/opt/emqx/bin/emqx", "ctl", "status");
                return result.getExitCode() == 0;
            } catch (Exception e) {
                return false;
            }
        });
        log.info("EMQX is running again");
    }

    /** 向容器主进程发送 KILL 后原地启动同一容器，保留固定宿主机端口，用于复现 broker 非正常退出。 */
    void killEmqxAndRestart() {
        log.info("Killing the EMQX container with SIGKILL");
        var docker = DockerClientFactory.instance().client();
        docker.killContainerCmd(emqx.getContainerId()).withSignal("KILL").exec();
        await().atMost(Duration.ofSeconds(30)).until(() -> !docker.inspectContainerCmd(emqx.getContainerId())
                .exec().getState().getRunning());
        docker.startContainerCmd(emqx.getContainerId()).exec();
        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(2)).until(() -> {
            try {
                return emqx.execInContainer("/opt/emqx/bin/emqx", "ctl", "status").getExitCode() == 0;
            } catch (Exception e) {
                return false;
            }
        });
        log.info("EMQX is running again after SIGKILL");
    }

    /** 每个场景开始前：把被结束的进程重新启动，并等到有已连接的主 transport。 */
    void ensureRunning() throws IOException {
        if (!server.alive()) {
            startServer();
        }
        startTransports();
        awaitLeaderConnected(Duration.ofSeconds(90));
    }

    /** InfluxDB 中该产品最近 1 小时的 Ua 点数；Ua 列还不存在（全新的库）时为 0。 */
    long influxCount(String productKey) throws Exception {
        JsonNode column = influxSql("SELECT data_type FROM information_schema.columns "
                + "WHERE table_name = 'property' AND column_name = 'Ua'", Map.of());
        if (column.isEmpty()) {
            return 0;
        }
        JsonNode rows = influxSql("SELECT COUNT(\"Ua\") AS n FROM property WHERE product_key = $pk "
                + "AND time >= now() - INTERVAL '1 hour'", Map.of("pk", productKey));
        return rows.isEmpty() ? 0 : rows.get(0).get("n").asLong();
    }

    /** 参数化 SQL 查询（POST /api/v3/query_sql，SQL 中用 $name 引用 params），与 server 的 InfluxClient 同一调用形式。 */
    private JsonNode influxSql(String query, Map<String, Object> params) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("db", "telemetry");
        payload.put("q", query);
        payload.put("params", params);
        payload.put("format", "json");
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(influxUrl() + "/api/v3/query_sql"))
                        .header("Authorization", "Bearer " + influxToken)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload),
                                StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("InfluxDB query failed with " + response.statusCode() + ": "
                    + response.body());
        }
        String body = response.body();
        return body == null || body.isBlank() ? JSON.createArrayNode() : JSON.readTree(body);
    }

    /** 死信流的全部内容（redis-cli 原样输出）。 */
    String deadLetters() throws Exception {
        return redis.execInContainer("redis-cli", "XRANGE", "conduit:stream:dead", "-", "+").getStdout();
    }

    /** 消费组 conduit-server 中待确认的条目数；读取失败（如消费组还不存在）时为 -1。 */
    long pendingCount() throws Exception {
        String out = redis.execInContainer("redis-cli", "XPENDING", "conduit:stream:up", "conduit-server")
                .getStdout().trim();
        String first = out.isEmpty() ? "" : out.lines().findFirst().orElse("").trim();
        try {
            return Long.parseLong(first);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 结束全部进程并停止容器；单个失败只记 WARN。 */
    @Override
    public void close() {
        if (server != null) {
            server.kill();
        }
        transports.forEach(AppProcess::kill);
        for (GenericContainer<?> c : containers) {
            try {
                c.stop();
            } catch (RuntimeException e) {
                log.warn("Could not stop container {}: {}", c.getDockerImageName(), e.getMessage());
            }
        }
        log.info("e2e environment removed; logs are in {}", logDir);
    }

    private String influxUrl() {
        return "http://" + influx.getHost() + ":" + influx.getMappedPort(8181);
    }

    private Path requireJar(String relative) {
        Path jar = root.resolve(relative);
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException(jar + " is missing: run mvn -B -DskipTests install first");
        }
        return jar;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static String hex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }
}
