package dev.conduit.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import dev.conduit.simulator.ConduitApiClient;
import org.awaitility.core.ConditionTimeoutException;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;

/**
 * e2e 的独立临时环境：Testcontainers 在 Docker 宿主机上起 PostgreSQL / Redis / InfluxDB / EMQX（内存持久会话、单节点，
 * 鉴权回调指向 E2E_DEV_HOST 上的 server），测试机本地以 jar 启动 1 个 server 与 3 个 transport 连接它们。
 * 3 个 transport 同时工作，按 Redis 租约分摊身份池（初始 K = {@value #INITIAL_POOL_SIZE}，比部署默认 64 小，缩短再平衡时间）。
 * 提供故障注入（强制结束 / 正常停止进程、重启 EMQX 容器、在线调大 K）与检查（InfluxDB 计数、死信、待确认数、身份分配）。
 * 口令与 token 每次运行随机生成，只存在于内存与子进程环境变量中；结束时进程与容器全部清理。
 */
final class E2eEnvironment implements AutoCloseable {

    static final String ADMIN_USERNAME = "admin";
    static final Duration CLAIM_MIN_IDLE = Duration.ofSeconds(10);
    /** transport 实例名，下标即 {@link #killTransport(int)} 等方法的参数。 */
    static final List<String> TRANSPORT_NAMES = List.of("transport-a", "transport-b", "transport-c");
    /** 初始身份池大小 K：经 TRANSPORT_POOL_SIZE 传给每个 transport，首个启动的实例把它写入 Redis。 */
    static final int INITIAL_POOL_SIZE = 16;
    /**
     * 等待身份分配收敛的上限。最坏情况是「负载下 1 个扩到 3 个」：新实例 JVM 启动（测试机上 10～30 秒）
     * + 成员列表不变 rebalance-stable 10 秒（两个新实例先后加入时重新计时）+ 原实例从 16 个让到精确配额 5 或 6 个、
     * 每 2 秒一次检查只让出一个（约 20～22 秒）+ 接手者连接（失败退避最长 10 秒），约 70 秒。
     * 强杀时：死实例的租约与心跳 15 秒后才过期，在此之前它仍在成员列表里，列表过期变化后再等 10 秒稳定才让出；
     * EMQX 重启后各身份按退避重连。取 2 分钟留出余量。
     */
    static final Duration REBALANCE_TIMEOUT = Duration.ofMinutes(2);

    private static final Logger log = LoggerFactory.getLogger(E2eEnvironment.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int EMQX_MQTT_PORT = 1883;
    /** 只供 transport 使用的内部监听器（tcp:internal，zone transport），与部署编排一致。 */
    private static final int EMQX_INTERNAL_PORT = 1884;
    /** 与 transport 的 RedisIdentityLeaseStore 一致的键：身份池大小与身份租约（值为持有者的 instanceId）。 */
    private static final String POOL_SIZE_KEY = "conduit:transport:pool-size";
    private static final String IDENTITY_KEY_PREFIX = "conduit:transport:id:";

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
    /** 应当在运行的 transport：启动时加入，被结束或停止时移出；它们若意外退出，等待分配收敛时立即报错。 */
    private final Set<AppProcess> wantedTransports = ConcurrentHashMap.newKeySet();

    private GenericContainer<?> postgres;
    private GenericContainer<?> redis;
    private GenericContainer<?> influx;
    private GenericContainer<?> emqx;
    private int emqxPort;
    private int emqxInternalPort;
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
     * 搭建整套环境，并等到 3 个 transport 分完全部身份且都已连接。
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

        for (String name : TRANSPORT_NAMES) {
            Map<String, String> transportEnv = new LinkedHashMap<>();
            transportEnv.put("REDIS_HOST", redis.getHost());
            transportEnv.put("REDIS_PORT", String.valueOf(redis.getMappedPort(6379)));
            transportEnv.put("EMQX_HOST", emqx.getHost());
            // transport 连内部监听器，设备（测试中的发压客户端）连 1883
            transportEnv.put("EMQX_PORT", String.valueOf(emqxInternalPort));
            transportEnv.put("TRANSPORT_MQTT_USERNAME", "conduit-transport");
            transportEnv.put("TRANSPORT_MQTT_PASSWORD", transportPassword);
            // 初始 K 只经这一个变量设置：mqtt.connections 与 allocation.initial-pool-size 都取自它，单独设其一会启动失败
            transportEnv.put("TRANSPORT_POOL_SIZE", String.valueOf(INITIAL_POOL_SIZE));
            // e2e 验证接入可靠性，不验证会话监控（需 EMQX API key）；名字必须是 TRANSPORT_*，CONDUIT_* 会被 AppProcess 清掉
            transportEnv.put("TRANSPORT_MONITOR_ENABLED", "false");
            transportEnv.put("CONDUIT_LOG_DIR", logDir.resolve(name + "/logs").toString());
            transports.add(new AppProcess(name, transportJar, transportEnv, freePort(), logDir.resolve(name)));
        }
        startTransports();
        awaitAllIdentitiesHeld(REBALANCE_TIMEOUT);
    }

    /**
     * 启动 EMQX：配置由部署模板渲染，内存持久会话（关闭磁盘持久会话）与固定节点名同 compose；设备端口在 20000～29999 中随机固定、内部监听器端口为其 +10000
     * （docker restart 后端口不变），被占用时换端口重试，最多 5 次。
     */
    private GenericContainer<?> startEmqx() throws IOException {
        String hocon = EmqxConfig.render(Files.readString(root.resolve("deploy/dev/emqx/base.hocon.tpl")),
                "http://" + devHost + ":" + serverPort, internalToken);
        byte[] acl = Files.readAllBytes(root.resolve("deploy/dev/emqx/acl.conf"));
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            int hostPort = 20000 + ThreadLocalRandom.current().nextInt(10000);
            int internalHostPort = hostPort + 10000;
            GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse("emqx/emqx:5.10.5"))
                    .withExposedPorts(EMQX_MQTT_PORT, EMQX_INTERNAL_PORT)
                    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                            new PortBinding(Ports.Binding.bindPort(hostPort), new ExposedPort(EMQX_MQTT_PORT)),
                            new PortBinding(Ports.Binding.bindPort(internalHostPort), new ExposedPort(EMQX_INTERNAL_PORT))))
                    .withCopyToContainer(Transferable.of(hocon.getBytes(StandardCharsets.UTF_8)),
                            "/opt/emqx/etc/base.hocon")
                    .withCopyToContainer(Transferable.of(acl), "/opt/emqx/etc/conduit-acl.conf")
                    // 与部署编排一致：关闭磁盘持久会话（积压下会静默楔死），改用内存持久会话；
                    // max_inflight 须不小于 transport 的 batch-size（默认 64），且宜为其若干倍：与部署默认 1024/64 一致
                    .withEnv("EMQX_DURABLE_SESSIONS__ENABLE", "false")
                    .withEnv("EMQX_MQTT__MAX_MQUEUE_LEN", "100000")
                    .withEnv("EMQX_MQTT__MAX_INFLIGHT", "512")
                    .withEnv("EMQX_FORCE_SHUTDOWN__MAX_HEAP_SIZE", "256MB")
                    // transport 专用 zone：与部署编排默认值一致（信箱 20 万、单连接会话队列 1.5 万，64 × 1.5 万 ≈ 96 万）
                    .withEnv("EMQX_ZONES__TRANSPORT__FORCE_SHUTDOWN__MAX_MAILBOX_SIZE", "200000")
                    .withEnv("EMQX_ZONES__TRANSPORT__MQTT__MAX_MQUEUE_LEN", "15000")
                    // 共享订阅轮询分发（下游不依赖顺序）；键在 mqtt 下，与部署编排一致
                    .withEnv("EMQX_MQTT__SHARED_SUBSCRIPTION_STRATEGY", "round_robin")
                    .withEnv("EMQX_CLUSTER__DISCOVERY_STRATEGY", "singleton")
                    .withEnv("EMQX_NODE__NAME", "emqx@127.0.0.1")
                    .withEnv("EMQX_DASHBOARD__DEFAULT_PASSWORD", hex(16))
                    .waitingFor(Wait.forLogMessage(".*is running now.*", 1))
                    .withStartupTimeout(Duration.ofMinutes(2));
            try {
                c.start();
                emqxPort = hostPort;
                emqxInternalPort = internalHostPort;
                containers.add(c);
                log.info("EMQX is running on {}:{} with in-memory persistent sessions", c.getHost(), hostPort);
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

    /** 只供 transport 使用的内部监听器（tcp:internal）在宿主机上的端口；设备连上来应被鉴权拒绝。 */
    int mqttInternalPort() {
        return emqxInternalPort;
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

    /** 强制结束全部 transport 进程（模拟同时崩溃）。 */
    void killTransports() {
        for (int i = 0; i < transports.size(); i++) {
            killTransport(i);
        }
    }

    /** 启动全部 transport（已在运行的不受影响），不等待就绪。 */
    void startTransports() throws IOException {
        for (int i = 0; i < transports.size(); i++) {
            startTransport(i);
        }
    }

    /**
     * 强制结束第 i 个 transport（模拟崩溃：不释放租约、不离开成员集合、不发 MQTT DISCONNECT）。
     * 它持有的身份要等租约过期（15 秒）后才能被其他实例接管，期间落给这些身份的消息排在 EMQX 会话队列里。
     */
    void killTransport(int i) {
        AppProcess transport = transports.get(i);
        wantedTransports.remove(transport);
        transport.kill();
    }

    /**
     * 正常停止第 i 个 transport（SIGTERM）：分配器停止时释放租约并离开成员集合，其他实例下一次检查即可接手，
     * 不必等租约过期。用于缩容场景；不支持正常终止的平台上退化为强制结束（见 {@link AppProcess#stop()}）。
     */
    void stopTransport(int i) {
        AppProcess transport = transports.get(i);
        wantedTransports.remove(transport);
        transport.stop();
    }

    /** 启动第 i 个 transport（已在运行时什么也不做），不等待就绪；之后由 {@link #awaitAllIdentitiesHeld} 等它分到身份。 */
    void startTransport(int i) throws IOException {
        AppProcess transport = transports.get(i);
        transport.start();
        wantedTransports.add(transport);
    }

    /**
     * 在线调整身份池大小 K：直接写 Redis 键 conduit:transport:pool-size，各实例在下一次检查（2 秒内）读到。
     * transport 只采用变大的 K，调小会被忽略，所以本方法只用于调大。
     */
    void setPoolSize(int k) throws Exception {
        ExecResult result = redis.execInContainer("redis-cli", "SET", POOL_SIZE_KEY, Integer.toString(k));
        if (result.getExitCode() != 0 || !result.getStdout().trim().equals("OK")) {
            throw new IllegalStateException("Could not set " + POOL_SIZE_KEY + " to " + k + ": "
                    + result.getStdout() + result.getStderr());
        }
        log.info("Identity pool size set to {} in Redis", k);
    }

    /**
     * Redis 中当前的身份池大小 K。
     *
     * @return K；键还不存在（尚无 transport 启动过）时为 0
     */
    int poolSize() throws Exception {
        String out = redis.execInContainer("redis-cli", "GET", POOL_SIZE_KEY).getStdout().trim();
        return out.isEmpty() ? 0 : Integer.parseInt(out);
    }

    /**
     * 正在运行的各 transport 持有的身份数（健康检查 components.allocation.details.held），键为实例名。
     * 进程未运行或健康检查读不到的实例不在结果中。
     */
    Map<String, Integer> heldByInstance() {
        Map<String, Integer> held = new TreeMap<>();
        for (AppProcess transport : transports) {
            readAllocation(transport).ifPresent(a -> held.put(transport.name(), a.held()));
        }
        return held;
    }

    /**
     * 等到身份分配收敛：应当运行的 transport 都在运行，且
     * <ul>
     *   <li>每个运行中的实例健康检查可读、connected 为 true（持有的身份都已连上），members 等于运行中的实例数，
     *       poolSize 等于 Redis 中的 K；</li>
     *   <li>各实例 held 之和等于 K，最多与最少之差 ≤ 1；</li>
     *   <li>Redis 中身份 0～K-1 的租约都存在，且持有者都是运行中的实例（不是已死实例尚未过期的租约）。</li>
     * </ul>
     * members 一项保证收敛不是过渡态：被强杀实例的心跳过期前，幸存者的份额仍按旧 N 计算。
     *
     * @throws AssertionError        超时，附最后一次看到的问题
     * @throws IllegalStateException 应当运行的 transport 意外退出（立即报错并指明其日志）
     */
    void awaitAllIdentitiesHeld(Duration timeout) {
        AtomicReference<String> lastProblem = new AtomicReference<>();
        try {
            await().atMost(timeout).pollInterval(Duration.ofSeconds(1)).until(() -> {
                lastProblem.set(allocationProblem());
                return lastProblem.get() == null;
            });
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("Identities did not settle within " + timeout + ": " + lastProblem.get(), e);
        }
        log.info("All identities are held and connected: {}", heldByInstance());
    }

    /**
     * 检查一次身份分配，规则见 {@link #awaitAllIdentitiesHeld}。
     *
     * @return 不满足的第一条原因；已收敛时为 null
     * @throws IllegalStateException 应当运行的 transport 已退出
     */
    private String allocationProblem() throws Exception {
        for (AppProcess transport : wantedTransports) {
            if (!transport.alive()) {
                throw new IllegalStateException(transport.name() + " exited unexpectedly, see "
                        + transport.consoleLog());
            }
        }
        int k = poolSize();
        if (k <= 0) {
            return "pool size is not in Redis yet";
        }
        List<Allocation> running = new ArrayList<>();
        for (AppProcess transport : transports) {
            if (!transport.alive()) {
                continue;
            }
            Optional<Allocation> allocation = readAllocation(transport);
            if (allocation.isEmpty()) {
                return transport.name() + " has no readable allocation health yet";
            }
            running.add(allocation.get());
        }
        if (running.isEmpty()) {
            return "no transport is running";
        }
        int sum = 0;
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (Allocation a : running) {
            if (!a.connected()) {
                return a.name() + " has identities that are not connected";
            }
            if (a.members() != running.size()) {
                return a.name() + " sees " + a.members() + " members, " + running.size() + " are running";
            }
            if (a.poolSize() != k) {
                return a.name() + " uses pool size " + a.poolSize() + ", Redis has " + k;
            }
            sum += a.held();
            min = Math.min(min, a.held());
            max = Math.max(max, a.held());
        }
        if (sum != k || max - min > 1) {
            return "held identities " + heldOf(running) + " do not add up to " + k + " evenly";
        }
        Set<String> runningIds = new HashSet<>();
        running.forEach(a -> runningIds.add(a.instanceId()));
        List<String> owners = leaseOwners(k);
        for (int i = 0; i < k; i++) {
            String owner = i < owners.size() ? owners.get(i) : "";
            if (!runningIds.contains(owner)) {
                return "identity #" + i + " is " + (owner.isEmpty() ? "not leased"
                        : "leased by " + owner + ", which is not a running transport");
            }
        }
        return null;
    }

    /** 身份 0～k-1 的租约持有者，按序号排列（MGET；redis-cli 非终端输出每个值一行，不存在的键为空行）。 */
    private List<String> leaseOwners(int k) throws Exception {
        List<String> command = new ArrayList<>(List.of("redis-cli", "MGET"));
        for (int i = 0; i < k; i++) {
            command.add(IDENTITY_KEY_PREFIX + i);
        }
        return redis.execInContainer(command.toArray(String[]::new)).getStdout().lines().map(String::trim).toList();
    }

    private static Map<String, Integer> heldOf(List<Allocation> allocations) {
        Map<String, Integer> held = new TreeMap<>();
        allocations.forEach(a -> held.put(a.name(), a.held()));
        return held;
    }

    /** 一个 transport 健康检查中的身份分配明细（components.allocation.details）。 */
    private record Allocation(String name, String instanceId, int held, int members, int poolSize,
                              boolean connected) {
    }

    /**
     * 读取 transport 的身份分配明细。健康检查 DOWN 时返回 503，但响应体仍含明细，照常解析。
     *
     * @return 进程未运行、端口未就绪或响应中没有 allocation 组件时为空
     */
    private Optional<Allocation> readAllocation(AppProcess transport) {
        String body = transport.get("/actuator/health");
        if (body == null) {
            return Optional.empty();
        }
        try {
            JsonNode details = JSON.readTree(body).path("components").path("allocation").path("details");
            if (!details.has("held")) {
                return Optional.empty();
            }
            return Optional.of(new Allocation(transport.name(), details.path("instanceId").asText(),
                    details.path("held").asInt(), details.path("members").asInt(),
                    details.path("poolSize").asInt(), details.path("connected").asBoolean()));
        } catch (IOException e) {
            log.debug("Unreadable health of {}: {}", transport.name(), e.getMessage());
            return Optional.empty();
        }
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

    /** 每个场景开始前：把被结束或停止的进程重新启动，并等到 3 个 transport 重新分完全部身份。 */
    void ensureRunning() throws IOException {
        if (!server.alive()) {
            startServer();
        }
        startTransports();
        awaitAllIdentitiesHeld(REBALANCE_TIMEOUT);
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
