package dev.conduit.e2e;

import dev.conduit.simulator.DeviceCredentials;
import dev.conduit.simulator.PayloadFormat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;

/** 可靠上报 e2e 设备组；计数只在平台业务回执到达后推进。 */
final class ReliableDeviceFleet {

    private final List<ReliableE2eDevice> devices;
    private final int messagesPerDevice;
    private final Duration interval;
    private final AtomicInteger acknowledged = new AtomicInteger();
    private volatile boolean stopped;

    ReliableDeviceFleet(String host, int port, List<DeviceCredentials> credentials, int messagesPerDevice,
                        Duration interval) {
        this.devices = credentials.stream().map(c -> new ReliableE2eDevice(host, port, c)).toList();
        this.messagesPerDevice = messagesPerDevice;
        this.interval = interval;
    }

    CompletableFuture<Void> start() {
        List<CompletableFuture<Void>> runs = new ArrayList<>();
        for (ReliableE2eDevice device : devices) {
            CompletableFuture<Void> run = new CompletableFuture<>();
            Thread.ofPlatform().name("e2e-reliable-" + device.deviceKey()).daemon().start(() -> {
                try {
                    send(device);
                    run.complete(null);
                } catch (Throwable t) {
                    run.completeExceptionally(t);
                } finally {
                    device.close();
                }
            });
            runs.add(run);
        }
        return CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new));
    }

    private void send(ReliableE2eDevice device) throws InterruptedException {
        String topic = "conduit/%s/%s/up/property".formatted(device.productKey(), device.deviceKey());
        long lastTs = 0;
        long next = System.currentTimeMillis();
        for (int seq = 1; seq <= messagesPerDevice; seq++) {
            long ts = Math.max(System.currentTimeMillis(), lastTs + 1);
            lastTs = ts;
            String id = device.deviceKey() + "-" + seq;
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("Ua", 220 + seq / 100.0);
            byte[] payload = PayloadFormat.STANDARD.encode(id, ts, params);
            device.publishUntilPlatformAcked(topic, id, payload, () -> stopped);
            if (stopped) {
                return;
            }
            acknowledged.incrementAndGet();
            next += interval.toMillis();
            long wait = next - System.currentTimeMillis();
            if (wait > 0) {
                Thread.sleep(wait);
            }
        }
    }

    void stop() {
        stopped = true;
        devices.forEach(ReliableE2eDevice::close);
    }

    /** 全部设备累计重发次数：用来判断故障是否真的迫使设备重发过（仅观测，不作断言）。 */
    int retransmissions() {
        return devices.stream().mapToInt(ReliableE2eDevice::retransmissions).sum();
    }

    int acknowledged() {
        return acknowledged.get();
    }

    long total() {
        return (long) devices.size() * messagesPerDevice;
    }

    void awaitAcknowledged(int count, Duration timeout) {
        await().atMost(timeout).pollInterval(Duration.ofMillis(200)).until(() -> acknowledged.get() >= count);
    }
}
