package dev.conduit.e2e;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5ConnAckException;
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAckReasonCode;
import dev.conduit.simulator.ConduitApiClient;
import dev.conduit.simulator.DeviceCredentials;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 可靠性端到端测试（V0.2-R spec 第 4 节，transport 多活 spec 第 9 节）：在独立临时环境中，3 个 transport 按身份池多活
 * （初始 K = 16），10 台设备 × 50 条 QoS1 上报的发送中途分别
 * ①强制结束 server 数秒后重启；②强制结束任一 transport；③3 个 transport 都结束、设备继续上报约 20 秒后重启；
 * ④重启 EMQX 容器（带 id 的可靠设备，靠设备重发不丢）；⑤可靠上报期间强杀 EMQX 后原地启动；
 * ⑥从 1 个实例扩到 3 个（每台 200 条，使负载覆盖整个迁移）；⑦从 3 个缩到 1 个（正常停止）；⑧同时强制结束 2 个；
 * ⑨把 K 从 16 调到 32（K 只增不减，排在最后）。
 * 另有场景 0：设备凭据连只供 transport 使用的内部监听器必须被拒（验证 EMQX 回调确实带上 X-Conduit-Listener）。
 * 每个场景断言：InfluxDB 中该产品恰好为发送总数、死信流中没有该产品、消费组 conduit-server 没有待确认消息；
 * 最后等到全部身份都有运行中的实例持有、各实例持有数相差不超过 1。结束时删除所建产品与设备。
 * 每个场景开始前把 server 与 3 个 transport 恢复运行并等分配收敛，所以场景之间互不依赖（K 除外，见场景 9）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class ReliabilityE2EIT {

    private static final Logger log = LoggerFactory.getLogger(ReliabilityE2EIT.class);
    static final int DEVICES = 10;
    static final int MESSAGES_PER_DEVICE = 50;
    static final Duration INTERVAL = Duration.ofMillis(500);
    /**
     * 场景 6 每台设备的条数：200 × 500ms ≈ 100 秒。1 扩到 3 的迁移约需 40～70 秒（新实例启动、成员数稳定 10 秒、
     * 每 2 秒让出一个身份共 10～11 个），默认的 50 条（25 秒）会在迁移完成前发完。
     */
    static final int SCALE_OUT_MESSAGES_PER_DEVICE = 200;
    static final Duration REBALANCE_TIMEOUT = E2eEnvironment.REBALANCE_TIMEOUT;

    /** 场景注入的故障。 */
    @FunctionalInterface
    interface Fault {
        void inject() throws Exception;
    }

    E2eEnvironment env;

    @BeforeAll
    void setUpEnvironment() throws Exception {
        env = E2eEnvironment.start();
    }

    @AfterAll
    void tearDownEnvironment() {
        if (env != null) {
            env.close();
        }
    }

    /** 每个场景开始前确保 server 与 3 个 transport 都在运行且身份分配已收敛（上个场景可能留下被结束或停止的进程）。 */
    @BeforeEach
    void everyProcessIsRunning() throws Exception {
        env.ensureRunning();
    }

    /**
     * 场景 0：同一台设备连设备监听器成功、连内部监听器被拒（NOT_AUTHORIZED）。
     * MqttAuthIT 只验证了 server 见到请求头后会拒绝；这里在真实 EMQX 上验证内部监听器的鉴权链确实带上了该请求头，
     * 防止模板改动漏掉它而让设备进入信箱与队列放宽的 transport zone。
     */
    @Test
    @Order(0)
    void devicesAreRejectedOnTheInternalListener() throws Exception {
        try (ConduitApiClient api = env.api()) {
            String productKey = api.findOrCreateProduct("e2e 内部监听器隔离 电表", "standard-json");
            try {
                DeviceCredentials d = api.createDevice(productKey, "e2e 内部监听器隔离 电表01");
                Mqtt5BlockingClient onDevicePort = deviceClient(d, env.mqttPort());
                onDevicePort.connect();
                onDevicePort.disconnect();

                assertThatThrownBy(() -> deviceClient(d, env.mqttInternalPort()).connect())
                        .isInstanceOfSatisfying(Mqtt5ConnAckException.class, e -> assertThat(
                                e.getMqttMessage().getReasonCode()).isEqualTo(Mqtt5ConnAckReasonCode.NOT_AUTHORIZED));
            } finally {
                cleanUp(api, productKey);
            }
        }
    }

    /** 用设备自己的凭据（clientId = 用户名 = deviceKey）建一个连指定端口的阻塞客户端。 */
    private Mqtt5BlockingClient deviceClient(DeviceCredentials d, int port) {
        return MqttClient.builder().useMqttVersion5().identifier(d.deviceKey())
                .serverHost(env.mqttHost()).serverPort(port)
                .simpleAuth().username(d.deviceKey()).password(d.secret().getBytes(StandardCharsets.UTF_8))
                .applySimpleAuth()
                .buildBlocking();
    }

    /** 场景 1：发送中途强制结束 server，3 秒后重启，数据一条不丢。 */
    @Test
    @Order(1)
    void killingTheServerMidStreamLosesNothing() throws Exception {
        runScenario(1, () -> {
            env.killServer();
            Thread.sleep(3_000);
            env.startServer();
        });
    }

    /**
     * 场景 2：强制结束任一 transport（固定取 transport-a），它的身份在租约过期（15 秒）后由另外两个实例接管，
     * 期间落给这些身份的消息排在 EMQX 会话队列里、接管后补发，数据一条不丢。
     */
    @Test
    @Order(2)
    void killingAnyTransportLetsTheOthersTakeOverItsIdentities() throws Exception {
        runScenario(2, () -> {
            env.killTransport(0);
            env.awaitAllIdentitiesHeld(REBALANCE_TIMEOUT);
            assertThat(env.heldByInstance()).as("the survivors hold every identity")
                    .containsOnlyKeys("transport-b", "transport-c");
        });
    }

    /** 场景 3：3 个 transport 都被结束约 20 秒，由 EMQX 内存持久会话的 mqueue 缓存消息，重启后重新分配身份并补齐。 */
    @Test
    @Order(3)
    void killingEveryTransportIsCoveredByTheSessionQueue() throws Exception {
        runScenario(3, () -> {
            env.killTransports();
            Thread.sleep(20_000);
            env.startTransports();
            env.awaitAllIdentitiesHeld(REBALANCE_TIMEOUT);
        });
    }

    /**
     * 场景 4：重启 EMQX 容器。内存持久会话不保留未投递消息，所以本场景用带 id 的可靠上报设备——
     * 不丢由设备侧重发保证，而不是由 broker 的持久化保证（磁盘持久会话已于 2026-10-28 关闭）。
     * 不带 id 的设备在本场景下会丢消息，这是设备接入契约中明确声明的缺口。
     */
    @Test
    @Order(4)
    void restartingEmqxIsCoveredByReliableDeviceRetransmission() throws Exception {
        runReliableScenario(4, () -> {
            env.restartEmqx();
            env.awaitAllIdentitiesHeld(REBALANCE_TIMEOUT);
        });
    }

    /** 场景 5：强杀 broker 会丢失其内存消息，但设备未收到业务回执时原样重发，最终仍恰好入库 500 条。 */
    @Test
    @Order(5)
    void reliableDevicesCoverAnEmqxProcessKill() throws Exception {
        runReliableScenario(5, () -> {
            env.killEmqxAndRestart();
            env.awaitAllIdentitiesHeld(REBALANCE_TIMEOUT);
        });
    }

    /**
     * 场景 6：负载下从 1 个实例扩到 3 个。先正常停止 transport-b / -c，等 transport-a 独占全部 16 个身份；
     * 发送中途启动两者，transport-a 在成员列表不变 10 秒后每 2 秒让出一个身份，
     * 直到三者按精确配额持有 6 / 5 / 5（哪个实例多持 1 个由实例 id 的字典序决定，重启后 id 会变）。
     * 被让出的身份先断开、会话留在 EMQX 排队，接手者以同一 clientId 接管会话后补发，数据一条不丢。
     * 迁移约需 40～70 秒，所以本场景每台发 {@value #SCALE_OUT_MESSAGES_PER_DEVICE} 条（约 100 秒），
     * 并断言迁移完成时设备仍在发送，保证整个迁移过程都在负载之下。
     */
    @Test
    @Order(6)
    void scalingOutFromOneToThreeUnderLoadLosesNothing() throws Exception {
        env.stopTransport(1);
        env.stopTransport(2);
        env.awaitAllIdentitiesHeld(REBALANCE_TIMEOUT);
        assertThat(env.heldByInstance()).as("one instance holds every identity before scaling out")
                .isEqualTo(Map.of("transport-a", env.poolSize()));
        runScenario(6, SCALE_OUT_MESSAGES_PER_DEVICE, true, () -> {
            env.startTransport(1);
            env.startTransport(2);
            env.awaitAllIdentitiesHeld(REBALANCE_TIMEOUT);
            assertThat(env.heldByInstance()).as("every instance holds a share after scaling out")
                    .containsOnlyKeys(E2eEnvironment.TRANSPORT_NAMES.toArray(String[]::new));
        });
    }

    /**
     * 场景 7：负载下从 3 个实例缩到 1 个。依次正常停止 transport-b / -c（SIGTERM）：它们停止时释放租约、离开成员集合，
     * transport-a 下一次检查即接手全部身份，不必等租约过期；数据一条不丢。
     */
    @Test
    @Order(7)
    void scalingInFromThreeToOneUnderLoadLosesNothing() throws Exception {
        runScenario(7, () -> {
            env.stopTransport(1);
            env.stopTransport(2);
            env.awaitAllIdentitiesHeld(REBALANCE_TIMEOUT);
            assertThat(env.heldByInstance()).as("the remaining instance holds every identity")
                    .isEqualTo(Map.of("transport-a", env.poolSize()));
        });
    }

    /**
     * 场景 8：同时强制结束 2 个 transport（transport-b / -c，相隔不到 1 秒）。两者的租约与心跳约 15 秒后过期，
     * transport-a 随即接手它们的全部身份；期间的消息排在会话队列里，接管后补发，数据一条不丢。
     */
    @Test
    @Order(8)
    void killingTwoTransportsAtOnceLeavesTheLastOneHoldingEverything() throws Exception {
        runScenario(8, () -> {
            env.killTransport(1);
            env.killTransport(2);
            env.awaitAllIdentitiesHeld(REBALANCE_TIMEOUT);
            assertThat(env.heldByInstance()).as("the survivor holds every identity")
                    .isEqualTo(Map.of("transport-a", env.poolSize()));
        });
    }

    /**
     * 场景 9：负载下把身份池 K 从 16 调到 32（直接写 Redis 键）。各实例 2 秒内读到新 K，精确配额变为 11 / 11 / 10，
     * 各自抢占新身份 16～31 并以共享订阅加入同一分组；数据一条不丢。
     * K 只增不减，所以本场景排在最后：之后的场景（若有）按 Redis 中的当前 K 判断分配是否收敛。
     */
    @Test
    @Order(9)
    void raisingThePoolSizeUnderLoadLosesNothing() throws Exception {
        assertThat(env.poolSize()).as("the pool still has its initial size").isEqualTo(E2eEnvironment.INITIAL_POOL_SIZE);
        int raised = E2eEnvironment.INITIAL_POOL_SIZE * 2;
        runScenario(9, MESSAGES_PER_DEVICE, true, () -> {
            env.setPoolSize(raised);
            env.awaitAllIdentitiesHeld(REBALANCE_TIMEOUT);
            assertThat(env.heldByInstance().values().stream().mapToInt(Integer::intValue).sum())
                    .as("identities held after raising the pool size").isEqualTo(raised);
        });
    }

    /**
     * 可靠上报场景：建产品「e2e 场景 N 电表」与 10 台带 id 的设备，确认 100 条平台回执后注入故障，
     * 等全部消息获得平台回执，再断言入库恰好、无死信、无待确认，最后等身份分配收敛；无论成败都清理本场景数据。
     */
    private void runReliableScenario(int n, Fault fault) throws Exception {
        try (ConduitApiClient api = env.api()) {
            ReliableDeviceFleet started = null;
            String productKey = api.findOrCreateProduct("e2e 场景 " + n + " 电表", "standard-json");
            try {
                List<DeviceCredentials> devices = new ArrayList<>();
                for (int i = 1; i <= DEVICES; i++) {
                    devices.add(api.createDevice(productKey, "e2e 场景%d 电表%02d".formatted(n, i)));
                }
                ReliableDeviceFleet fleet = new ReliableDeviceFleet(env.mqttHost(), env.mqttPort(), devices,
                        MESSAGES_PER_DEVICE, INTERVAL);
                started = fleet;
                CompletableFuture<Void> sending = fleet.start();
                fleet.awaitAcknowledged(100, Duration.ofSeconds(60));
                assertThat(sending.isDone()).as("the fault must land mid-stream").isFalse();
                log.info("Scenario {}: injecting the fault after {} platform acknowledgements", n,
                        fleet.acknowledged());

                fault.inject();
                sending.get(5, TimeUnit.MINUTES);
                log.info("Scenario {}: device-side retransmissions = {}", n, fleet.retransmissions());

                await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(3))
                        .untilAsserted(() -> assertThat(env.influxCount(productKey)).isEqualTo(fleet.total()));
                assertThat(env.deadLetters()).doesNotContain(productKey);
                await().atMost(E2eEnvironment.CLAIM_MIN_IDLE.plusSeconds(60)).pollInterval(Duration.ofSeconds(3))
                        .untilAsserted(() -> assertThat(env.pendingCount()).isZero());
                assertThat(env.influxCount(productKey)).isEqualTo(fleet.total());
                env.awaitAllIdentitiesHeld(REBALANCE_TIMEOUT);
            } finally {
                if (started != null) {
                    started.stop();
                }
                cleanUp(api, productKey);
            }
        }
    }

    /** 默认负载（每台 {@value #MESSAGES_PER_DEVICE} 条）的场景，不要求负载持续到故障处理完毕，见下一个重载。 */
    private void runScenario(int n, Fault fault) throws Exception {
        runScenario(n, MESSAGES_PER_DEVICE, false, fault);
    }

    /**
     * 一个场景：建产品「e2e 场景 N 电表」与 10 台设备，开始发送，确认 100 条后注入故障，等发送结束，再做三项断言，
     * 最后等身份分配收敛；无论成败都删除本场景的设备与产品。
     *
     * @param messagesPerDevice    每台设备发送的条数
     * @param loadMustOutlastFault 为 true 时断言故障注入（含其中的等待，如等迁移完成）返回时设备仍在发送，
     *                             即整个过程都在负载之下；用于「负载下」扩容与调大 K 的场景
     */
    private void runScenario(int n, int messagesPerDevice, boolean loadMustOutlastFault, Fault fault)
            throws Exception {
        try (ConduitApiClient api = env.api()) {
            DeviceFleet started = null;
            String productKey = api.findOrCreateProduct("e2e 场景 " + n + " 电表", "standard-json");
            try {
                List<DeviceCredentials> devices = new ArrayList<>();
                for (int i = 1; i <= DEVICES; i++) {
                    devices.add(api.createDevice(productKey, "e2e 场景%d 电表%02d".formatted(n, i)));
                }
                DeviceFleet fleet = new DeviceFleet(env.mqttHost(), env.mqttPort(), devices, messagesPerDevice,
                        INTERVAL);
                started = fleet;
                CompletableFuture<Void> sending = fleet.start();
                fleet.awaitAcked(100, Duration.ofSeconds(60));
                assertThat(sending.isDone()).as("the fault must land mid-stream").isFalse();
                log.info("Scenario {}: injecting the fault after {} acknowledged messages", n, fleet.acked());

                fault.inject();
                if (loadMustOutlastFault) {
                    assertThat(sending.isDone()).as("devices must still be sending when the fault has been handled "
                            + "(%d of %d acknowledged)", fleet.acked(), fleet.total()).isFalse();
                }
                sending.get(5, TimeUnit.MINUTES);
                log.info("Scenario {}: all {} messages acknowledged by EMQX", n, fleet.total());

                await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(3))
                        .untilAsserted(() -> assertThat(env.influxCount(productKey))
                                .as("points of scenario %d in InfluxDB", n).isEqualTo(fleet.total()));
                assertThat(env.deadLetters()).as("dead letters of scenario %d", n).doesNotContain(productKey);
                await().atMost(E2eEnvironment.CLAIM_MIN_IDLE.plusSeconds(60)).pollInterval(Duration.ofSeconds(3))
                        .untilAsserted(() -> assertThat(env.pendingCount())
                                .as("pending entries in group conduit-server").isZero());
                assertThat(env.influxCount(productKey)).as("no extra points after the backlog drained")
                        .isEqualTo(fleet.total());
                env.awaitAllIdentitiesHeld(REBALANCE_TIMEOUT);
            } finally {
                if (started != null) {
                    started.stop();
                }
                cleanUp(api, productKey);
            }
        }
    }

    /** 删除本场景的设备与产品（产品下还有设备时服务端返回 409，所以先删设备）；失败只记 WARN，不掩盖测试结果。 */
    private static void cleanUp(ConduitApiClient api, String productKey) {
        try {
            for (String deviceKey : api.listDeviceKeys(productKey)) {
                api.deleteDevice(deviceKey);
            }
            api.deleteProduct(productKey);
        } catch (RuntimeException e) {
            log.warn("Failed to clean up e2e product {}", productKey, e);
        }
    }
}
