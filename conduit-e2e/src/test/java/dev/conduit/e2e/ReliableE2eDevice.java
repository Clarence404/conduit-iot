package dev.conduit.e2e;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5PublishResult;
import dev.conduit.simulator.ApplicationAck;
import dev.conduit.simulator.DeviceCredentials;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 第五个可靠性场景使用的设备：订阅平台业务回执，消息在收到对应 id 前一直保留并原样重发；连接丢失时重建连接与订阅。
 */
final class ReliableE2eDevice implements AutoCloseable {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration RETRY_DELAY = Duration.ofMillis(200);
    private static final Duration GIVE_UP_AFTER = Duration.ofMinutes(5);

    private final String host;
    private final int port;
    private final DeviceCredentials credentials;
    /** 本设备累计重发次数（同一条消息第一次之外的发送）。 */
    private final AtomicInteger retransmissions = new AtomicInteger();
    private final ConcurrentHashMap<String, CompletableFuture<Void>> waiting = new ConcurrentHashMap<>();
    private Mqtt5AsyncClient client;

    ReliableE2eDevice(String host, int port, DeviceCredentials credentials) {
        this.host = host;
        this.port = port;
        this.credentials = credentials;
    }

    String deviceKey() {
        return credentials.deviceKey();
    }

    String productKey() {
        return credentials.productKey();
    }

    /** 本设备累计重发次数。 */
    int retransmissions() {
        return retransmissions.get();
    }

    /** 重发同一载荷直到收到匹配业务回执；PUBACK 只表示本次发送完成，不会从本地待确认表删除。 */
    void publishUntilPlatformAcked(String topic, String id, byte[] payload, BooleanSupplier stopped)
            throws InterruptedException {
        CompletableFuture<Void> acknowledged = new CompletableFuture<>();
        waiting.put(id, acknowledged);
        long deadline = System.nanoTime() + GIVE_UP_AFTER.toNanos();
        int attempts = 0;
        try {
            while (!stopped.getAsBoolean() && !acknowledged.isDone()) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("Device " + deviceKey()
                            + " did not receive platform ack within " + GIVE_UP_AFTER);
                }
                try {
                    ensureConnected();
                    attempts++;
                    Mqtt5PublishResult result = client.publishWith().topic(topic).qos(MqttQos.AT_LEAST_ONCE)
                            .payload(payload).send().get(PUBLISH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    if (result.getError().isPresent()) {
                        throw new IllegalStateException(result.getError().get());
                    }
                    acknowledged.get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    if (client == null || !client.getState().isConnected()) {
                        dropConnection();
                    }
                } catch (ExecutionException | RuntimeException e) {
                    dropConnection();
                    Thread.sleep(RETRY_DELAY.toMillis());
                }
            }
        } finally {
            // 第一次之外的每次发送都是重发（设备没等到业务回执）
            if (attempts > 1) {
                retransmissions.addAndGet(attempts - 1);
            }
            waiting.remove(id, acknowledged);
        }
    }

    /** 未连接时重建客户端，并在继续上报前完成当前设备的业务回执订阅。 */
    private void ensureConnected() throws InterruptedException, ExecutionException, TimeoutException {
        if (client != null && client.getState().isConnected()) {
            return;
        }
        dropConnection();
        client = MqttClient.builder().useMqttVersion5().identifier(deviceKey())
                .serverHost(host).serverPort(port)
                .simpleAuth().username(deviceKey()).password(credentials.secret().getBytes(StandardCharsets.UTF_8))
                .applySimpleAuth().buildAsync();
        client.connect().get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        client.subscribeWith().topicFilter("conduit/%s/%s/down/ack".formatted(productKey(), deviceKey()))
                .qos(MqttQos.AT_MOST_ONCE)
                .callback(p -> ApplicationAck.id(p.getPayloadAsBytes()).ifPresent(id -> {
                    CompletableFuture<Void> future = waiting.get(id);
                    if (future != null) {
                        future.complete(null);
                    }
                })).send().get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void dropConnection() {
        Mqtt5AsyncClient c = client;
        client = null;
        if (c != null) {
            try {
                c.disconnect().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Broker 被强杀时连接本就不可用。
            }
        }
    }

    @Override
    public void close() {
        dropConnection();
    }
}
