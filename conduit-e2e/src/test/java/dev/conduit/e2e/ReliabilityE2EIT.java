package dev.conduit.e2e;

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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * V0.2-R 可靠性端到端测试（spec 第 4 节）：在独立临时环境中，10 台设备 × 50 条 QoS1 上报的发送中途分别
 * ①强制结束 server 数秒后重启；②强制结束主 transport；③两个 transport 都结束、设备继续上报约 20 秒后重启；④重启 EMQX 容器（带 id 的可靠设备，靠设备重发不丢）；
 * ⑤可靠上报期间强杀 EMQX 后原地启动。
 * 每个场景断言：InfluxDB 中该产品恰好 500 条、死信流中没有该产品、消费组 conduit-server 没有待确认消息；结束时删除所建产品与设备。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class ReliabilityE2EIT {

    private static final Logger log = LoggerFactory.getLogger(ReliabilityE2EIT.class);
    static final int DEVICES = 10;
    static final int MESSAGES_PER_DEVICE = 50;
    static final Duration INTERVAL = Duration.ofMillis(500);

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

    /** 每个场景开始前确保 server 与 transport 都在运行（上个场景可能留下被结束的进程）。 */
    @BeforeEach
    void everyProcessIsRunning() throws Exception {
        env.ensureRunning();
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

    /** 场景 2：强制结束主 transport，备用实例接管，数据一条不丢。 */
    @Test
    @Order(2)
    void killingTheLeaderTransportLetsTheStandbyTakeOver() throws Exception {
        runScenario(2, () -> {
            AppProcess leader = env.leaderTransport();
            env.kill(leader);
            AppProcess successor = env.awaitLeaderConnected(Duration.ofSeconds(60));
            assertThat(successor).as("the standby takes over").isNotSameAs(leader);
        });
    }

    /** 场景 3：两个 transport 都被结束约 20 秒，由 EMQX 内存持久会话的 mqueue 缓存消息，重启后补齐。 */
    @Test
    @Order(3)
    void killingEveryTransportIsCoveredByTheSessionQueue() throws Exception {
        runScenario(3, () -> {
            env.killTransports();
            Thread.sleep(20_000);
            env.startTransports();
            env.awaitLeaderConnected(Duration.ofSeconds(90));
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
            env.awaitLeaderConnected(Duration.ofMinutes(2));
        });
    }

    /** 场景 5：强杀 broker 会丢失其内存消息，但设备未收到业务回执时原样重发，最终仍恰好入库 500 条。 */
    @Test
    @Order(5)
    void reliableDevicesCoverAnEmqxProcessKill() throws Exception {
        runReliableScenario(5, () -> {
            env.killEmqxAndRestart();
            env.awaitLeaderConnected(Duration.ofMinutes(2));
        });
    }

    /**
     * 可靠上报场景：建产品「e2e 场景 N 电表」与 10 台带 id 的设备，确认 100 条平台回执后注入故障，
     * 等全部消息获得平台回执，再断言入库恰好、无死信、无待确认；无论成败都清理本场景数据。
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
            } finally {
                if (started != null) {
                    started.stop();
                }
                cleanUp(api, productKey);
            }
        }
    }

    /**
     * 一个场景：建产品「e2e 场景 N 电表」与 10 台设备，开始发送，确认 100 条后注入故障，等发送结束，再做三项断言；
     * 无论成败都删除本场景的设备与产品。
     */
    private void runScenario(int n, Fault fault) throws Exception {
        try (ConduitApiClient api = env.api()) {
            DeviceFleet started = null;
            String productKey = api.findOrCreateProduct("e2e 场景 " + n + " 电表", "standard-json");
            try {
                List<DeviceCredentials> devices = new ArrayList<>();
                for (int i = 1; i <= DEVICES; i++) {
                    devices.add(api.createDevice(productKey, "e2e 场景%d 电表%02d".formatted(n, i)));
                }
                DeviceFleet fleet = new DeviceFleet(env.mqttHost(), env.mqttPort(), devices, MESSAGES_PER_DEVICE,
                        INTERVAL);
                started = fleet;
                CompletableFuture<Void> sending = fleet.start();
                fleet.awaitAcked(100, Duration.ofSeconds(60));
                assertThat(sending.isDone()).as("the fault must land mid-stream").isFalse();
                log.info("Scenario {}: injecting the fault after {} acknowledged messages", n, fleet.acked());

                fault.inject();
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
