# EMQX 会话楔死修复 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除 EMQX 持久会话在积压下的静默楔死，使任何消息丢失都可见、有界、可自愈。

**Architecture:** 关闭 EMQX 磁盘持久会话改用内存持久会话（楔死随之消失，失败模式变为可见的 mqueue 溢出）；transport 投递线程由「每条一次 XADD」改为微批，把持续投递上限从实测约 1,230 条/秒提上去以减少溢出；transport 主实例经 EMQX REST API 轮询会话指标，对丢弃、队列水位、会话进程内存与投递停滞告警。

**Tech Stack:** Java 21、Spring Boot 3.5、Spring Data Redis（Streams）、HiveMQ MQTT Client、EMQX 5.10.5、Docker Compose、JUnit 5 + Testcontainers。

**Spec:** [`docs/superpowers/specs/2026-10-28-conduit-emqx-session-wedge-fix-design.md`](../specs/2026-10-28-conduit-emqx-session-wedge-fix-design.md)

**实测依据:** [`docs/test-reports/2026-10-28-emqx-durable-session-wedge.md`](../../test-reports/2026-10-28-emqx-durable-session-wedge.md)

## Global Constraints

- 注释用中文，每个类写类级 Javadoc，每个重点方法写 Javadoc（说明做什么、为什么、不明显的边界与副作用）；修改代码时同步更新注释，不留过期注释。
- 日志用 SLF4J 参数化；**绝不记录设备密钥、口令、JWT、内部 token、Authorization、EMQX API key/secret**；构造字符串或集合的日志用 `isDebugEnabled()` / `isTraceEnabled()` 保护。
- 先写失败测试（TDD）：每个任务都是「写测试 → 跑测试确认失败 → 实现 → 跑测试确认通过 → 提交」。
- 提交用 Conventional Commits，**不加任何 AI 署名行**。
- 不把 IP、本机路径、用户名、口令、token 写进受版本控制的文件；`.env.example` 只给占位值。
- **`conduit.transport.mqtt.batch-size` 与 EMQX 的 `mqtt.max_inflight` 必须相等**，本期均取 `32`；两处注释都要写明这条耦合，压测时作为一对同步上调（64 / 256 / 1024）再复测。不等则批永远攒不满、每批白等满 `batch-delay`，或窗口没用满。
- 批大小「不限」的既有约定不变：`conduit.pipeline.batch-size` 为 `-1` 时表示不限，但 `XPENDING` 的 COUNT 必须为正数（`RedisStreamMessageBus.DEFAULT_CLAIM_COUNT = 1000`）。本计划不改动该路径。
- 遥测语义不变：null/缺省表示未上报，0 是有效值；禁止缺测补零。
- 构建命令：`mvn -B -o verify`。只跑单模块时必须先 `mvn -pl conduit-common install`，否则会用到本地仓库里的旧 jar。
- 环境变量 `DOCKER_HOST` 指向开发虚拟机，Testcontainers 据此运行。

---

### Task 1: MessageBus 批量发布

**Files:**
- Create: `conduit-common/src/main/java/dev/conduit/common/bus/PublishResult.java`
- Modify: `conduit-common/src/main/java/dev/conduit/common/bus/MessageBus.java`
- Modify: `conduit-common/src/main/java/dev/conduit/common/bus/RedisStreamMessageBus.java`
- Test: `conduit-common/src/test/java/dev/conduit/common/bus/RedisStreamMessageBusIT.java`（已存在，追加用例）

**Interfaces:**
- Consumes: 既有的 `RawMessage`、`RawMessageCodec.toFields(RawMessage)`、`RedisStreamMessageBus` 私有的 `bytes(String)`、`props.streamKey()`、`props.maxLen()`
- Produces: `MessageBus.publish(List<RawMessage>) -> List<PublishResult>`，以及 `PublishResult.ok(String)` / `PublishResult.failed(RuntimeException)` / `PublishResult.id()` / `PublishResult.failure()` / `PublishResult.succeeded()`。Task 2 依赖这些方法名与「返回值与入参等长同序」的契约。

- [ ] **Step 1: 写失败的集成测试**

在 `RedisStreamMessageBusIT` 中追加（类上已有的 Testcontainers Redis 容器与 `bus` 字段沿用）：

```java
@Test
void publishesBatchInOrderAndReturnsOneResultPerMessage() {
    List<RawMessage> messages = List.of(
            new RawMessage("pk", "dev-1", MessageType.PROPERTY, "{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                    1_000L, Map.of("topic", "t1")),
            new RawMessage("pk", "dev-2", MessageType.PROPERTY, "{\"a\":2}".getBytes(StandardCharsets.UTF_8),
                    2_000L, Map.of("topic", "t2")));

    List<PublishResult> results = bus.publish(messages);

    assertThat(results).hasSize(2);
    assertThat(results).allMatch(PublishResult::succeeded);
    // 条目 id 单调递增，确认写入顺序与入参一致
    assertThat(results.get(0).id()).isLessThan(results.get(1).id());

    bus.ensureGroup("g-batch");
    List<Delivery> read = bus.read("g-batch", "c1", 10, Duration.ofSeconds(1));
    assertThat(read).extracting(d -> d.message().deviceKey()).containsExactly("dev-1", "dev-2");
}

@Test
void emptyBatchPublishesNothing() {
    assertThat(bus.publish(List.of())).isEmpty();
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -B -o test -pl conduit-common -Dtest=RedisStreamMessageBusIT`

Expected: 编译失败——`publish(List)` 与 `PublishResult` 不存在。

- [ ] **Step 3: 新建 PublishResult**

`conduit-common/src/main/java/dev/conduit/common/bus/PublishResult.java`：

```java
package dev.conduit.common.bus;

/**
 * 批量发布中单条消息的结果。{@code id} 与 {@code failure} 恰有一个非空——
 * Redis 管道不是原子的，一批里可以部分成功，调用方必须逐条区分，
 * 否则整批重发会把已成功的消息重复写入总线。
 *
 * @param id      成功时总线分配的条目 id；失败时为 null
 * @param failure 失败时的异常；成功时为 null
 */
public record PublishResult(String id, RuntimeException failure) {

    public PublishResult {
        if ((id == null) == (failure == null)) {
            throw new IllegalArgumentException("PublishResult requires exactly one of id / failure");
        }
    }

    /** 成功时用。 */
    public static PublishResult ok(String id) {
        return new PublishResult(id, null);
    }

    /** 失败时用。 */
    public static PublishResult failed(RuntimeException failure) {
        return new PublishResult(null, failure);
    }

    /** 是否发布成功。 */
    public boolean succeeded() {
        return id != null;
    }
}
```

- [ ] **Step 4: 在 MessageBus 接口中声明批量发布**

在 `String publish(RawMessage message);` 之后插入：

```java
    /**
     * 批量发布到上行流，返回与入参**等长、同序**的结果列表。
     *
     * <p>实现以一次往返提交整批（Redis 为管道），但**不保证原子**：允许部分成功，
     * 因此逐条返回成败而不是整批抛异常。调用方应当只确认成功的那些消息，
     * 对失败的不确认、由上游重投——整批重发会重复写入已成功的条目。
     *
     * <p>空列表返回空列表，不产生任何命令。
     */
    List<PublishResult> publish(List<RawMessage> messages);
```

- [ ] **Step 5: 在 RedisStreamMessageBus 中实现**

在 `publish(RawMessage)` 之后插入：

```java
    /**
     * 管道提交整批 XADD：一次网络往返，吞吐不再受「每条消息一次往返」的限制。
     * 管道中某条失败不影响其他条，所以逐条映射结果；返回值与入参等长同序。
     */
    @Override
    public List<PublishResult> publish(List<RawMessage> messages) {
        if (messages.isEmpty()) {
            return List.of();
        }
        List<Object> raw = redis.executePipelined((RedisCallback<Object>) c -> {
            for (RawMessage message : messages) {
                Map<byte[], byte[]> fields = new LinkedHashMap<>();
                RawMessageCodec.toFields(message).forEach((k, v) -> fields.put(bytes(k), bytes(v)));
                c.streamCommands().xAdd(
                        StreamRecords.rawBytes(fields).withStreamKey(bytes(props.streamKey())),
                        XAddOptions.maxlen(props.maxLen()).approximateTrimming(true));
            }
            return null;
        });
        List<PublishResult> results = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            Object value = i < raw.size() ? raw.get(i) : null;
            if (value instanceof RecordId id) {
                results.add(PublishResult.ok(id.getValue()));
            } else if (value instanceof Exception e) {
                results.add(PublishResult.failed(new IllegalStateException("XADD failed", e)));
            } else {
                results.add(PublishResult.failed(
                        new IllegalStateException("XADD returned no id for " + props.streamKey())));
            }
        }
        return results;
    }
```

在文件顶部补充 `import java.util.ArrayList;`。

- [ ] **Step 6: 跑测试确认通过**

Run: `mvn -B -o test -pl conduit-common -Dtest=RedisStreamMessageBusIT`

Expected: PASS，含既有用例全部通过。

- [ ] **Step 7: 提交**

```bash
git add conduit-common/src/main/java/dev/conduit/common/bus/PublishResult.java conduit-common/src/main/java/dev/conduit/common/bus/MessageBus.java conduit-common/src/main/java/dev/conduit/common/bus/RedisStreamMessageBus.java conduit-common/src/test/java/dev/conduit/common/bus/RedisStreamMessageBusIT.java
git commit -m "feat(bus): 支持批量发布，一次往返提交整批 XADD"
```

---

### Task 2: transport 微批写入总线

**Files:**
- Modify: `conduit-transport/src/main/java/dev/conduit/transport/mqtt/MqttTransportProperties.java`
- Modify: `conduit-transport/src/main/java/dev/conduit/transport/mqtt/MqttProtocolAdapter.java`
- Modify: `conduit-transport/src/main/resources/application.yml`
- Test: `conduit-transport/src/test/java/dev/conduit/transport/mqtt/MqttProtocolAdapterTest.java`（已存在，追加用例）

**Interfaces:**
- Consumes: Task 1 的 `MessageBus.publish(List<RawMessage>)` 与 `PublishResult`
- Produces: 配置项 `conduit.transport.mqtt.batch-size`、`conduit.transport.mqtt.batch-delay`。Task 5 的压测断言引用这两项。

**背景（实施者必读）：** 现状是 `onPublish` 每收到一条消息就调一次 `bus.publish(message)`，即一次 Redis 往返；`Session.deliveryExecutor` 是单线程，所以持续投递上限实测只有约 1,230 条/秒，低于接入速率时 EMQX 的 mqueue 会溢出丢弃。本任务把提交改为攒批。**保序、回执语义、失败不 ACK 三条都不能变。**

- [ ] **Step 1: 写失败的单元测试**

`MqttProtocolAdapterTest` 中追加（沿用该类已有的 `bus` mock、`clock` 与构造被测对象的方式）：

```java
@Test
void batchesPublishesUntilBatchSizeIsReached() {
    // 批大小 2：收到两条即提交一次，两条都 ACK
    MqttProtocolAdapter adapter = adapterWithBatch(2, Duration.ofSeconds(30));
    MqttProtocolAdapter.Session s = adapter.newSession();
    when(bus.publish(anyList())).thenAnswer(inv -> okResults(inv.getArgument(0)));

    Mqtt5Publish first = publishOf("conduit/pk/dev-1/up/property", "{\"ts\":1}");
    Mqtt5Publish second = publishOf("conduit/pk/dev-2/up/property", "{\"ts\":2}");
    adapter.onPublish(s, first);
    adapter.onPublish(s, second);

    verify(bus, times(1)).publish(argThat((List<RawMessage> b) -> b.size() == 2));
    verify(first).acknowledge();
    verify(second).acknowledge();
}

@Test
void flushesPartialBatchAfterBatchDelay() {
    // 批大小 10、延迟 50ms：只来一条，也必须在延迟到期后提交，不能无限等
    MqttProtocolAdapter adapter = adapterWithBatch(10, Duration.ofMillis(50));
    MqttProtocolAdapter.Session s = adapter.newSession();
    when(bus.publish(anyList())).thenAnswer(inv -> okResults(inv.getArgument(0)));

    Mqtt5Publish only = publishOf("conduit/pk/dev-1/up/property", "{\"ts\":1}");
    adapter.onPublish(s, only);

    verify(bus, timeout(2000).times(1)).publish(argThat((List<RawMessage> b) -> b.size() == 1));
    verify(only, timeout(2000)).acknowledge();
}

@Test
void doesNotAcknowledgeTheFailedEntriesOfAPartiallySuccessfulBatch() {
    // 管道部分成功：成功的 ACK，失败的不 ACK，由 broker 重投
    MqttProtocolAdapter adapter = adapterWithBatch(2, Duration.ofSeconds(30));
    MqttProtocolAdapter.Session s = adapter.newSession();
    when(bus.publish(anyList())).thenReturn(List.of(
            PublishResult.ok("1-0"),
            PublishResult.failed(new IllegalStateException("boom"))));

    Mqtt5Publish good = publishOf("conduit/pk/dev-1/up/property", "{\"ts\":1}");
    Mqtt5Publish bad = publishOf("conduit/pk/dev-2/up/property", "{\"ts\":2}");
    adapter.onPublish(s, good);
    adapter.onPublish(s, bad);

    verify(good, timeout(2000)).acknowledge();
    verify(bad, never()).acknowledge();
}

@Test
void preservesArrivalOrderWithinABatch() {
    MqttProtocolAdapter adapter = adapterWithBatch(3, Duration.ofSeconds(30));
    MqttProtocolAdapter.Session s = adapter.newSession();
    when(bus.publish(anyList())).thenAnswer(inv -> okResults(inv.getArgument(0)));

    adapter.onPublish(s, publishOf("conduit/pk/dev-1/up/property", "{\"ts\":1}"));
    adapter.onPublish(s, publishOf("conduit/pk/dev-2/up/property", "{\"ts\":2}"));
    adapter.onPublish(s, publishOf("conduit/pk/dev-3/up/property", "{\"ts\":3}"));

    ArgumentCaptor<List<RawMessage>> batch = ArgumentCaptor.forClass(List.class);
    verify(bus).publish(batch.capture());
    assertThat(batch.getValue()).extracting(RawMessage::deviceKey)
            .containsExactly("dev-1", "dev-2", "dev-3");
}
```

辅助方法（同一测试类内）：

```java
/** 按指定批参数构造被测适配器；其余依赖沿用测试类已有的 mock。 */
private MqttProtocolAdapter adapterWithBatch(int batchSize, Duration batchDelay) {
    MqttTransportProperties p = new MqttTransportProperties("localhost", 1883, "conduit-transport",
            null, null, 86400L, "conduit-", batchSize, batchDelay);
    return new MqttProtocolAdapter(p, bus, clock, Duration.ofMillis(1));
}

/** 为整批生成全部成功的结果，id 按下标编号。 */
private static List<PublishResult> okResults(List<RawMessage> batch) {
    List<PublishResult> results = new ArrayList<>(batch.size());
    for (int i = 0; i < batch.size(); i++) {
        results.add(PublishResult.ok((i + 1) + "-0"));
    }
    return results;
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -B -o test -pl conduit-transport -Dtest=MqttProtocolAdapterTest`

Expected: 编译失败——`MqttTransportProperties` 没有 `batchSize` / `batchDelay` 两个分量。

- [ ] **Step 3: 扩展 MqttTransportProperties**

整个文件替换为：

```java
package dev.conduit.transport.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * transport 连接 EMQX 的 MQTT 配置（前缀 conduit.transport.mqtt）。
 *
 * @param clientId             所有 transport 实例共用的 clientId：EMQX 按 clientId 为离线的订阅端排队，新主实例接管同一会话
 * @param username             为空表示匿名连接（仅测试环境）
 * @param sessionExpirySeconds 会话保留秒数，决定 transport 离线多久以内 EMQX 仍为它排队
 * @param ownClientPrefix      平台内部客户端的 clientId 前缀，这些客户端的上下线事件不转发
 * @param batchSize            一次提交到总线的最大条数；1 等价于逐条提交（回退开关）
 * @param batchDelay           攒批的最长等待：不足 batchSize 时最多等这么久也要提交，
 *                             直接体现为 PUBACK 的最坏额外延迟
 */
@ConfigurationProperties(prefix = "conduit.transport.mqtt")
public record MqttTransportProperties(
        @DefaultValue("localhost") String host,
        @DefaultValue("1883") int port,
        @DefaultValue("conduit-transport") String clientId,
        String username,
        String password,
        @DefaultValue("86400") long sessionExpirySeconds,
        @DefaultValue("conduit-") String ownClientPrefix,
        @DefaultValue("64") int batchSize,
        @DefaultValue("5ms") Duration batchDelay) {

    public MqttTransportProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException("conduit.transport.mqtt.batch-size must be >= 1");
        }
    }
}
```

- [ ] **Step 4: 在 MqttProtocolAdapter 中攒批**

4.1 在类字段区增加共享定时器（所有会话共用一个，随适配器生命周期）：

```java
    /** 延迟提交的触发器：只负责把 flush 投回会话的投递线程，自身不做业务。 */
    private final ScheduledExecutorService flushTimer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("mqtt-flush-timer").daemon().factory());
```

4.2 在 `Session` 中，紧跟 `deliveryExecutor` 字段之后增加：

```java
        /** 本批待提交的条目，只在 deliveryExecutor 这一条线程上读写，无需加锁。 */
        final List<Pending> pending = new ArrayList<>();
```

4.3 在 `Session` 类之后增加：

```java
    /** 一条已解析、等待随批提交到总线的消息；提交成功后才 ACK 并发业务回执。 */
    private record Pending(Mqtt5Publish publish, ParsedTopic topic, RawMessage message) {
    }
```

4.4 把 `onPublish` 末尾原有的

```java
        if (publishWithRetry(s, message)) {
            if (t.type() == MessageType.PROPERTY) {
                sendReliableAck(s, t, message);
            }
            acknowledge(publish);
        }
    }
```

替换为

```java
        // 入队攒批：满 batchSize 立即提交，否则由延迟提交兜底，避免低速时消息干等
        s.pending.add(new Pending(publish, t, message));
        if (s.pending.size() >= props.batchSize()) {
            flush(s);
        } else if (s.pending.size() == 1) {
            scheduleFlush(s);
        }
    }

    /**
     * 安排一次延迟提交：批未攒满时，最多等 batchDelay 也要把队列发出去。
     * 提交统一回到 deliveryExecutor 执行，保证与 onPublish 串行、不需要加锁。
     */
    private void scheduleFlush(Session s) {
        flushTimer.schedule(() -> {
            if (!s.stopping) {
                s.deliveryExecutor.execute(() -> flush(s));
            }
        }, props.batchDelay().toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 提交当前批：一次总线调用，逐条按结果处理。
     * 成功的条目先发业务回执再 ACK；失败的条目不 ACK，由 broker 重投（至少一次）。
     * 整批调用抛异常时走阻塞退避重试；会话停止时放弃且不 ACK。
     */
    private void flush(Session s) {
        if (s.pending.isEmpty()) {
            return;
        }
        List<Pending> batch = List.copyOf(s.pending);
        s.pending.clear();
        List<PublishResult> results = publishBatchWithRetry(s, batch);
        if (results == null) {
            return; // 会话停止，一条都不 ACK
        }
        for (int i = 0; i < batch.size(); i++) {
            Pending p = batch.get(i);
            PublishResult r = results.get(i);
            if (!r.succeeded()) {
                log.warn("Bus publish failed for device {}, leaving it unacknowledged: {}",
                        p.message().deviceKey(), r.failure().getMessage());
                continue;
            }
            log.debug("Published to bus: id={} deviceKey={} type={}",
                    r.id(), p.message().deviceKey(), p.message().type());
            if (p.topic().type() == MessageType.PROPERTY) {
                sendReliableAck(s, p.topic(), p.message());
            }
            acknowledge(p.publish());
        }
    }

    /**
     * 整批提交并在**整体失败**时退避重试（下游不可用）。逐条失败不在这里重试——
     * 那些条目不 ACK、由 broker 重投，避免把已成功的条目重复写入总线。
     *
     * @return 与入参等长同序的结果；会话停止或线程被中断时返回 null
     */
    private List<PublishResult> publishBatchWithRetry(Session s, List<Pending> batch) {
        List<RawMessage> messages = batch.stream().map(Pending::message).toList();
        for (int attempt = 0; !s.stopping; attempt++) {
            try {
                return bus.publish(messages);
            } catch (RuntimeException e) {
                Duration delay = backoff(attempt);
                log.warn("Bus batch publish of {} messages failed (attempt {}), retrying in {} ms: {}",
                        messages.size(), attempt + 1, delay.toMillis(), e.getMessage());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }
```

4.5 在会话停止处（`s.deliveryExecutor.shutdownNow();` 之前）增加：

```java
        // 未提交的条目一律不 ACK：broker 会重投，重复由流水线按 ts 幂等吸收
        s.pending.clear();
```

4.6 删除已不再被调用的 `publishWithRetry(Session, RawMessage)`。

4.7 补充 import：`java.util.ArrayList`、`java.util.concurrent.ScheduledExecutorService`、`java.util.concurrent.TimeUnit`、`dev.conduit.common.bus.PublishResult`。

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -pl conduit-common install -B -o -DskipTests && mvn -B -o test -pl conduit-transport`

Expected: PASS，`MqttProtocolAdapterTest` 既有用例与 4 个新用例全部通过。

- [ ] **Step 6: 配置与注释**

`conduit-transport/src/main/resources/application.yml` 的 `conduit.transport.mqtt` 下追加：

```yaml
      # 微批写入总线：单线程投递下，每条一次 XADD 的持续上限实测约 1230 条/秒，
      # 低于接入速率时 EMQX 的 mqueue 会溢出丢弃。攒批把每条一次往返变为每批一次。
      # batch-delay 是 PUBACK 的最坏额外延迟；batch-size 设为 1 即退回逐条提交。
      batch-size: ${TRANSPORT_BUS_BATCH_SIZE:64}
      batch-delay: ${TRANSPORT_BUS_BATCH_DELAY:5ms}
```

同时把 `client-id` 与 `session-expiry-seconds` 上方提到「EMQX 磁盘持久会话」的注释改写为「EMQX 按 clientId 为离线的订阅端排队」（磁盘持久会话在 Task 3 关闭）。

- [ ] **Step 7: 提交**

```bash
git add conduit-transport/src/main/java/dev/conduit/transport/mqtt/MqttTransportProperties.java conduit-transport/src/main/java/dev/conduit/transport/mqtt/MqttProtocolAdapter.java conduit-transport/src/main/resources/application.yml conduit-transport/src/test/java/dev/conduit/transport/mqtt/MqttProtocolAdapterTest.java
git commit -m "feat(transport): 微批写入总线以提高持续投递上限"
```

---

### Task 3: 关闭磁盘持久会话并为内存会话定容

**Files:**
- Modify: `deploy/dev/docker-compose.yml`
- Modify: `deploy/dev/.env.example`
- Modify: `deploy/standalone/.env.example`

**Interfaces:**
- Consumes: 无
- Produces: 环境变量 `EMQX_MAX_MQUEUE_LEN`（默认 100000）。Task 4 的监控从 API 读 `mqueue_max`，不依赖本变量。

**背景：** 实测证明磁盘持久会话在积压下会静默楔死且不可恢复；内存持久会话在同一负载下投递 307,263 条并完全排空、超堆被杀 0 次。见 spec 第 1、3 节。

- [ ] **Step 1: 改 EMQX 服务的环境变量**

`deploy/dev/docker-compose.yml` 中把现有的这段

```yaml
      # 磁盘持久会话（V0.2-R）：transport 离线期间的消息写入磁盘，保留 1 天（durable_sessions.message_retention_period 默认值）；
      # 单节点磁盘持久会话使用 singleton。固定节点名：默认按容器 IP 命名，IP 变化会换数据目录、丢失持久会话
      EMQX_DURABLE_SESSIONS__ENABLE: "true"
      EMQX_CLUSTER__DISCOVERY_STRATEGY: singleton
      EMQX_NODE__NAME: emqx@127.0.0.1
      # 持久会话积压恢复可超过默认 32MB；仍保留单连接内存保护。
      EMQX_FORCE_SHUTDOWN__MAX_HEAP_SIZE: ${EMQX_CONNECTION_MAX_HEAP_SIZE:-256MB}
```

替换为

```yaml
      # 内存持久会话（2026-10-28）：磁盘持久会话在积压下会静默楔死且只能丢弃会话恢复，已关闭。
      # transport 离线期间的消息进内存队列 mqueue，重连后补发；EMQX 重启则不保留——
      # 端到端不丢由设备侧的消息 id + 业务回执负责，见设备接入契约。
      EMQX_DURABLE_SESSIONS__ENABLE: "false"
      # mqueue 要覆盖 transport 主备切换窗口（选主锁 TTL 15s + 抢锁 2s ≈ 17s）；
      # 溢出会丢弃并计入 mqueue_dropped，由 transport 的会话监控告警
      EMQX_MQTT__MAX_MQUEUE_LEN: ${EMQX_MAX_MQUEUE_LEN:-100000}
      EMQX_CLUSTER__DISCOVERY_STRATEGY: singleton
      # 固定节点名：默认按容器 IP 命名，IP 变化会换数据目录
      EMQX_NODE__NAME: emqx@127.0.0.1
      # 单连接进程堆上限（实际按 1.5 倍触发，即 384MB）。mqueue 驻留在会话进程堆内，
      # 与本项共享同一上限：调大 MAX_MQUEUE_LEN 前必须确认 heap_size 余量，不得反过来上调本项
      EMQX_FORCE_SHUTDOWN__MAX_HEAP_SIZE: ${EMQX_CONNECTION_MAX_HEAP_SIZE:-256MB}
```

- [ ] **Step 2: 同步两个 .env.example**

`deploy/dev/.env.example` 与 `deploy/standalone/.env.example` 的 EMQX 组中，把

```
# 单连接进程堆上限：持久会话恢复积压时默认 32MB 过小，但仍要保留有界保护
EMQX_CONNECTION_MAX_HEAP_SIZE=256MB
```

替换为

```
# transport 离线期间 EMQX 为它排队的最大条数。要覆盖主备切换窗口（约 17 秒）×峰值速率；
# 溢出按 mqueue_dropped 计数并告警。调大前先确认会话进程内存余量（见下一项）
EMQX_MAX_MQUEUE_LEN=100000
# 单连接进程堆上限，实际按 1.5 倍触发（即 384MB）。mqueue 驻留在会话进程堆内，与本项共享上限
EMQX_CONNECTION_MAX_HEAP_SIZE=256MB
```

- [ ] **Step 3: 验证编排可解析**

```bash
cd deploy/standalone && cp -n .env.example .env.verify && docker compose --env-file .env.verify config --quiet && rm -f .env.verify
```

Expected: `config --quiet` 无输出、退出码 0。

- [ ] **Step 4: 提交**

```bash
git add deploy/dev/docker-compose.yml deploy/dev/.env.example deploy/standalone/.env.example
git commit -m "fix(deploy): 关闭 EMQX 磁盘持久会话并为内存会话定容"
```

---

### Task 4: 经 EMQX REST API 监控会话

**Files:**
- Create: `conduit-transport/src/main/java/dev/conduit/transport/monitor/SessionSnapshot.java`
- Create: `conduit-transport/src/main/java/dev/conduit/transport/monitor/SessionMonitor.java`
- Test: `conduit-transport/src/test/java/dev/conduit/transport/monitor/SessionMonitorTest.java`

**Interfaces:**
- Consumes: 无（判定逻辑只依赖注入的 `Supplier<SessionSnapshot>`，便于测试）
- Produces: `SessionMonitor.sample()`、`SessionMonitor.verdict() -> SessionMonitor.Verdict(boolean healthy, String reason, Map<String,Object> details)`。Task 4b 的健康检查与定时轮询依赖这两个方法。

**字段名已实测核对（EMQX 5.10.5），不得凭印象改：** `mqueue_dropped`、`mqueue_len`、`mqueue_max`、`inflight_cnt`、`inflight_max`、`send_msg`（投递总数，**不是** `delivered_msgs`）、`heap_size`（Erlang 字，×8 为字节）、`durable`。

- [ ] **Step 1: 写失败的单元测试**

`conduit-transport/src/test/java/dev/conduit/transport/monitor/SessionMonitorTest.java`：

```java
package dev.conduit.transport.monitor;

import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** SessionMonitor 的五条判据：丢弃增长、队列水位、会话进程内存、投递停滞、配置漂移。 */
class SessionMonitorTest {

    /** 按给定字段造一份快照。 */
    private static SessionSnapshot snapshot(long dropped, long mqueueLen, long mqueueMax,
                                            long inflight, long inflightMax, long sendMsg,
                                            long heapWords, boolean durable) {
        return new SessionSnapshot(dropped, mqueueLen, mqueueMax, inflight, inflightMax,
                sendMsg, heapWords, durable);
    }

    private static SessionMonitor monitorOf(Supplier<SessionSnapshot> source, int stallSamples) {
        return new SessionMonitor(source, stallSamples, 256L * 1024 * 1024);
    }

    @Test
    void healthyWhenNothingIsWrong() {
        SessionMonitor m = monitorOf(() -> snapshot(0, 0, 100_000, 0, 32, 1_000, 1_000, false), 6);
        m.sample();
        assertThat(m.verdict().healthy()).isTrue();
    }

    @Test
    void reportsDroppedMessagesWhenTheCounterGrows() {
        long[] dropped = {0};
        SessionMonitor m = monitorOf(() -> snapshot(dropped[0], 0, 100_000, 0, 32, 1_000, 1_000, false), 6);
        m.sample();
        dropped[0] = 42;
        m.sample();
        // 丢弃是可见的数据丢失，但系统仍在推进：告警而不置 DOWN
        assertThat(m.verdict().healthy()).isTrue();
        assertThat(m.verdict().details()).containsEntry("mqueueDroppedDelta", 42L);
    }

    @Test
    void reportsStalledDeliveryAfterEnoughUnchangedSamples() {
        // inflight 占满 + 队列非空 + send_msg 连续不变 == 2026-10-28 那次故障的形状
        SessionMonitor m = monitorOf(() -> snapshot(0, 500, 100_000, 32, 32, 39_811, 1_000, false), 3);
        m.sample();
        m.sample();
        assertThat(m.verdict().healthy()).isTrue();
        m.sample();
        assertThat(m.verdict().healthy()).isFalse();
        assertThat(m.verdict().reason()).contains("stalled");
    }

    @Test
    void doesNotReportStallWhenDeliveryIsProgressing() {
        long[] sent = {0};
        SessionMonitor m = monitorOf(() -> snapshot(0, 500, 100_000, 32, 32, sent[0] += 100, 1_000, false), 3);
        for (int i = 0; i < 5; i++) {
            m.sample();
        }
        assertThat(m.verdict().healthy()).isTrue();
    }

    @Test
    void warnsWhenMqueueIsNearCapacity() {
        SessionMonitor m = monitorOf(() -> snapshot(0, 80_000, 100_000, 0, 32, 1_000, 1_000, false), 6);
        m.sample();
        assertThat(m.verdict().details()).containsEntry("mqueueNearCapacity", true);
    }

    @Test
    void warnsWhenSessionHeapApproachesTheLimit() {
        // 堆限 256MB，按 70% 告警；heap_size 单位是 Erlang 字（×8 字节）
        long words = (long) (0.75 * 256 * 1024 * 1024 / 8);
        SessionMonitor m = monitorOf(() -> snapshot(0, 0, 100_000, 0, 32, 1_000, words, false), 6);
        m.sample();
        assertThat(m.verdict().details()).containsEntry("heapNearLimit", true);
    }

    @Test
    void warnsWhenDurableSessionsAreUnexpectedlyEnabled() {
        SessionMonitor m = monitorOf(() -> snapshot(0, 0, 100_000, 0, 32, 1_000, 1_000, true), 6);
        m.sample();
        assertThat(m.verdict().details()).containsEntry("durable", true);
    }

    @Test
    void keepsThePreviousVerdictWhenPollingFails() {
        // 监控本身失败不得改变健康状态，否则 EMQX 抖动会制造假故障
        SessionMonitor m = monitorOf(() -> {
            throw new IllegalStateException("unreachable");
        }, 6);
        m.sample();
        assertThat(m.verdict().healthy()).isTrue();
        assertThat(m.verdict().details()).containsEntry("pollFailed", true);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -B -o test -pl conduit-transport -Dtest=SessionMonitorTest`

Expected: 编译失败——`SessionSnapshot` 与 `SessionMonitor` 不存在。

- [ ] **Step 3: 新建 SessionSnapshot**

```java
package dev.conduit.transport.monitor;

/**
 * EMQX 会话指标的一次采样。字段对应 {@code GET /api/v5/clients/{clientid}} 的返回
 * （EMQX 5.10.5 实测核对）：投递总数是 send_msg，而不是 CLI 里显示的 delivered_msgs。
 *
 * @param mqueueDropped 队列溢出丢弃的累计条数（真实的数据丢失）
 * @param mqueueLen     当前队列长度
 * @param mqueueMax     队列容量上限
 * @param inflightCnt   当前在途（已发出未确认）条数
 * @param inflightMax   在途窗口上限
 * @param sendMsg       已投递消息的累计条数
 * @param heapWords     会话进程堆占用，单位 Erlang 字（×8 为字节）
 * @param durable       是否为磁盘持久会话；本项目预期恒为 false
 */
public record SessionSnapshot(long mqueueDropped, long mqueueLen, long mqueueMax,
                              long inflightCnt, long inflightMax, long sendMsg,
                              long heapWords, boolean durable) {
}
```

- [ ] **Step 4: 新建 SessionMonitor**

```java
package dev.conduit.transport.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * EMQX 会话健康判定：按固定间隔采样，对「队列溢出丢弃」「队列逼近容量」「会话进程内存逼近堆限」
 * 「投递停滞」「磁盘持久会话被意外启用」五项给出结论。
 *
 * <p>「投递停滞」是对 2026-10-28 定位的那次故障的兜底：当时 inflight 占满、队列非空、
 * 投递总数永久不变，而所有既有信号都是绿的。关闭磁盘持久会话后预期不再发生，一旦复现必须能被发现。
 *
 * <p>采样失败不改变健康结论，只标记 pollFailed——监控本身的抖动不应制造故障。
 */
public class SessionMonitor {

    /** 队列长度占容量多少即预警。 */
    private static final double MQUEUE_WARN_RATIO = 0.8;
    /** 会话进程堆占堆限多少即预警。 */
    private static final double HEAP_WARN_RATIO = 0.7;
    /** Erlang 一个字的字节数。 */
    private static final int BYTES_PER_WORD = 8;

    private static final Logger log = LoggerFactory.getLogger(SessionMonitor.class);

    private final Supplier<SessionSnapshot> source;
    private final int stallSamples;
    private final long heapLimitBytes;

    private SessionSnapshot previous;
    private int unchangedSendSamples;
    private volatile Verdict verdict = new Verdict(true, "not sampled yet", Map.of());

    /**
     * @param source         采样来源（生产环境为 EMQX REST 客户端）
     * @param stallSamples   投递总数连续多少次采样不变才判定停滞
     * @param heapLimitBytes 配置的单连接堆上限字节数，用于计算内存预警阈值
     */
    public SessionMonitor(Supplier<SessionSnapshot> source, int stallSamples, long heapLimitBytes) {
        this.source = source;
        this.stallSamples = stallSamples;
        this.heapLimitBytes = heapLimitBytes;
    }

    /** 当前结论；从未成功采样时为健康。 */
    public Verdict verdict() {
        return verdict;
    }

    /**
     * 采样一次并更新结论。只有「投递停滞」会把健康状态置为 false；
     * 其余各项是告警——丢弃虽然是真实的数据丢失，但系统仍在正常推进。
     */
    public void sample() {
        SessionSnapshot now;
        try {
            now = source.get();
        } catch (RuntimeException e) {
            // 保留上次结论：EMQX 短暂不可达不等于会话有问题
            Map<String, Object> details = new LinkedHashMap<>(verdict.details());
            details.put("pollFailed", true);
            verdict = new Verdict(verdict.healthy(), verdict.reason(), Map.copyOf(details));
            log.warn("EMQX session poll failed, keeping the previous verdict: {}", e.getMessage());
            return;
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mqueueLen", now.mqueueLen());
        details.put("sendMsg", now.sendMsg());

        if (previous != null && now.mqueueDropped() > previous.mqueueDropped()) {
            long delta = now.mqueueDropped() - previous.mqueueDropped();
            details.put("mqueueDroppedDelta", delta);
            log.warn("EMQX dropped {} queued messages (total {}): ingest exceeds delivery capacity",
                    delta, now.mqueueDropped());
        }
        if (now.mqueueMax() > 0 && now.mqueueLen() >= now.mqueueMax() * MQUEUE_WARN_RATIO) {
            details.put("mqueueNearCapacity", true);
            log.warn("EMQX queue at {}/{}: messages will be dropped if it fills",
                    now.mqueueLen(), now.mqueueMax());
        }
        long heapBytes = now.heapWords() * BYTES_PER_WORD;
        if (heapLimitBytes > 0 && heapBytes >= heapLimitBytes * HEAP_WARN_RATIO) {
            details.put("heapNearLimit", true);
            log.warn("EMQX session process heap at {} bytes of a {} byte limit: "
                    + "reduce the queue capacity rather than raising the limit", heapBytes, heapLimitBytes);
        }
        if (now.durable()) {
            details.put("durable", true);
            log.warn("EMQX durable sessions are enabled; this platform expects in-memory sessions");
        }

        boolean deliveryBlocked = now.inflightCnt() >= now.inflightMax() && now.mqueueLen() > 0;
        unchangedSendSamples = (previous != null && deliveryBlocked && now.sendMsg() == previous.sendMsg())
                ? unchangedSendSamples + 1
                : 0;
        details.put("unchangedSendSamples", unchangedSendSamples);

        previous = now;
        if (unchangedSendSamples >= stallSamples) {
            String reason = "EMQX delivery stalled: inflight window full and send_msg unchanged for "
                    + unchangedSendSamples + " samples";
            log.error("{} (queued {})", reason, now.mqueueLen());
            verdict = new Verdict(false, reason, Map.copyOf(details));
        } else {
            verdict = new Verdict(true, "ok", Map.copyOf(details));
        }
    }

    /**
     * 一次判定的结论。
     *
     * @param healthy false 只在判定投递停滞时出现
     * @param reason  不健康的原因，健康时为 "ok"
     * @param details 写入健康检查明细的观测值
     */
    public record Verdict(boolean healthy, String reason, Map<String, Object> details) {
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -B -o test -pl conduit-transport -Dtest=SessionMonitorTest`

Expected: PASS，8 项全通过。

- [ ] **Step 6: 提交**

```bash
git add conduit-transport/src/main/java/dev/conduit/transport/monitor/ conduit-transport/src/test/java/dev/conduit/transport/monitor/SessionMonitorTest.java
git commit -m "feat(transport): 新增 EMQX 会话健康判定"
```

---

#### 追加要求
### 追加第 6 条判据：批大小与 inflight 窗口不相等即告警

**背景：** transport 攒批提交到消息总线，而「待提交条目数」恰好等于「EMQX 侧未确认的在途消息数」，后者被 `max_inflight` 硬限。所以：

- `batch-size > inflight_max` → 批**永远攒不满**，每一批都要白等满 `batch-delay`（5ms），吞吐被无谓拉低
- `batch-size < inflight_max` → 窗口没用满

两者必须**相等**。本项目已决定二者都取 32，并在压测时作为一对同步上调。这个耦合关系写在两处配置的注释里，但很容易被人只改一边，所以用监控把它钉死。

**改动：**

1. `SessionMonitor` 的构造器**增加第 4 个参数** `int batchSize`：

```java
    /**
     * @param source         采样来源（生产环境为 EMQX REST 客户端）
     * @param stallSamples   投递总数连续多少次采样不变才判定停滞
     * @param heapLimitBytes 配置的单连接堆上限字节数，用于计算内存预警阈值
     * @param batchSize      transport 攒批提交的批大小，必须与 EMQX 的 inflight 窗口相等（见下）
     */
    public SessionMonitor(Supplier<SessionSnapshot> source, int stallSamples,
                          long heapLimitBytes, int batchSize) {
```

2. 在 `sample()` 的告警部分增加一条（放在 `durable` 那条之后）：

```java
        // 批大小必须等于 inflight 窗口：大于则批永远攒不满、每批白等满 batch-delay，小于则窗口没用满。
        // 两处配置分属 transport 与 EMQX，很容易被只改一边，所以在这里钉死。
        if (now.inflightMax() > 0 && batchSize != now.inflightMax()) {
            details.put("batchSizeMismatch", now.inflightMax());
            log.warn("transport batch size {} does not match the EMQX inflight window {}: "
                    + "batches never fill (or the window is underused); these two must be kept equal",
                    batchSize, now.inflightMax());
        }
```

3. 本条**只告警，不影响健康状态**——与丢弃、队列水位、内存、配置漂移同级；只有「投递停滞」才置 DOWN。

4. 在 `SessionMonitorTest` 中增加两个用例：

```java
    @Test
    void warnsWhenBatchSizeDoesNotMatchTheInflightWindow() {
        // 批大小 64、窗口 32：批永远攒不满，每批白等满 batch-delay
        SessionMonitor m = new SessionMonitor(
                () -> snapshot(0, 0, 100_000, 0, 32, 1_000, 1_000, false), 6, 256L * 1024 * 1024, 64);
        m.sample();
        assertThat(m.verdict().details()).containsEntry("batchSizeMismatch", 32L);
        assertThat(m.verdict().healthy()).isTrue();
    }

    @Test
    void doesNotWarnWhenBatchSizeMatchesTheInflightWindow() {
        SessionMonitor m = new SessionMonitor(
                () -> snapshot(0, 0, 100_000, 0, 32, 1_000, 1_000, false), 6, 256L * 1024 * 1024, 32);
        m.sample();
        assertThat(m.verdict().details()).doesNotContainKey("batchSizeMismatch");
    }
```

5. 上文 brief 中原有的 `monitorOf(...)` 辅助方法要相应增加 `batchSize` 参数（原有用例一律传 `32`，与快照里的 `inflightMax=32` 相等，**避免原有用例因这条新判据而出现非预期的 details 项**）。

---

### Task 4b: 接入 EMQX REST 客户端、定时轮询与健康检查

**Files:**
- Create: `conduit-transport/src/main/java/dev/conduit/transport/monitor/SessionMonitorProperties.java`
- Create: `conduit-transport/src/main/java/dev/conduit/transport/monitor/EmqxSessionClient.java`
- Create: `deploy/dev/emqx/api-key.bootstrap.tpl`
- Modify: `conduit-transport/src/main/java/dev/conduit/transport/leader/LeaderHealthIndicator.java`
- Modify: transport 的 Spring 配置类（注册 `SessionMonitor` 与轮询任务的那个 `@Configuration`）
- Modify: `conduit-transport/src/main/resources/application.yml`
- Modify: `deploy/dev/docker-compose.yml`、`deploy/standalone/docker-compose.yml`、`deploy/dev/.env.example`、`deploy/standalone/.env.example`

**Interfaces:**
- Consumes: Task 4 的 `SessionMonitor`、`SessionSnapshot`；既有的 `LeaderElection.role()`、`LeaderElection.instanceId()`、`LeaderElection.adaptersConnected()`
- Produces: 健康检查 `components.leader.details` 中新增 `session` 与 `sessionError`

- [ ] **Step 1: 新建 SessionMonitorProperties**

```java
package dev.conduit.transport.monitor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * transport 轮询 EMQX 会话指标的配置（前缀 conduit.transport.monitor）。
 * EMQX 5 的 REST API 不接受控制台账号密码，必须用 API key/secret（实测返回 401 BAD_API_KEY_OR_SECRET）。
 *
 * @param enabled      关闭时不轮询，健康检查退回只看选主与连接状态
 * @param baseUrl      EMQX dashboard 的地址，如 http://emqx:18083
 * @param apiKey       API key，经 api_key.bootstrap_file 下发，角色取只读 viewer
 * @param apiSecret    API secret
 * @param interval     轮询间隔
 * @param stallSamples 投递总数连续多少次采样不变才判定停滞（默认 6，约 3 分钟）
 * @param heapLimit    EMQX 的单连接堆上限，须与编排中 EMQX_CONNECTION_MAX_HEAP_SIZE 保持一致
 */
@ConfigurationProperties(prefix = "conduit.transport.monitor")
public record SessionMonitorProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("http://localhost:18083") String baseUrl,
        String apiKey,
        String apiSecret,
        @DefaultValue("30s") Duration interval,
        @DefaultValue("6") int stallSamples,
        @DefaultValue("256MB") DataSize heapLimit) {
}
```

- [ ] **Step 2: 新建 EmqxSessionClient**

```java
package dev.conduit.transport.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Supplier;

/**
 * 读取 EMQX 的 {@code GET /api/v5/clients/{clientid}} 并映射为 {@link SessionSnapshot}。
 * 字段名以 EMQX 5.10.5 实际返回为准；缺字段按 0 / false 处理。
 * 认证用 API key/secret 的 Basic 认证，**密钥不得出现在日志中**。
 */
public class EmqxSessionClient implements Supplier<SessionSnapshot> {

    private final RestClient client;
    private final String clientId;

    public EmqxSessionClient(SessionMonitorProperties props, String clientId) {
        this.clientId = clientId;
        String credentials = Base64.getEncoder().encodeToString(
                (props.apiKey() + ":" + props.apiSecret()).getBytes(StandardCharsets.UTF_8));
        this.client = RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader("Authorization", "Basic " + credentials)
                .build();
    }

    /** 采样一次；HTTP 错误与解析失败都以异常抛出，由 SessionMonitor 转为 pollFailed。 */
    @Override
    public SessionSnapshot get() {
        JsonNode n = client.get()
                .uri("/api/v5/clients/{clientId}", clientId)
                .retrieve()
                .body(JsonNode.class);
        if (n == null) {
            throw new IllegalStateException("EMQX returned an empty body for client " + clientId);
        }
        return new SessionSnapshot(
                n.path("mqueue_dropped").asLong(),
                n.path("mqueue_len").asLong(),
                n.path("mqueue_max").asLong(),
                n.path("inflight_cnt").asLong(),
                n.path("inflight_max").asLong(),
                n.path("send_msg").asLong(),
                n.path("heap_size").asLong(),
                n.path("durable").asBoolean(false));
    }
}
```

- [ ] **Step 3: 注册 Bean 与轮询**

在 transport 的 `@Configuration` 中注册 `SessionMonitorProperties`（加入 `@EnableConfigurationProperties`）、`EmqxSessionClient`、`SessionMonitor`，并按 `interval` 调度：

```java
    /**
     * 按配置的间隔采样 EMQX 会话。只有主实例持有那条 MQTT 连接，
     * 备实例读到的不是自己的会话，不参与判定。
     */
    @Bean
    ScheduledExecutorService sessionMonitorScheduler(SessionMonitorProperties props,
                                                     SessionMonitor monitor,
                                                     LeaderElection election) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("session-monitor").daemon().factory());
        if (props.enabled()) {
            long millis = props.interval().toMillis();
            scheduler.scheduleWithFixedDelay(() -> {
                if (election.role() == Role.LEADER) {
                    monitor.sample();
                }
            }, millis, millis, TimeUnit.MILLISECONDS);
        }
        return scheduler;
    }
```

`SessionMonitor` 的 Bean：

```java
    @Bean
    SessionMonitor sessionMonitor(SessionMonitorProperties props, MqttTransportProperties mqtt) {
        return new SessionMonitor(new EmqxSessionClient(props, mqtt.clientId()),
                props.stallSamples(), props.heapLimit().toBytes());
    }
```

- [ ] **Step 4: 健康检查接入**

`LeaderHealthIndicator` 整类替换为：

```java
package dev.conduit.transport.leader;

import dev.conduit.transport.monitor.SessionMonitor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * 健康检查组件 leader（/actuator/health 的 components.leader）：details 含 role（LEADER / STANDBY）、
 * instanceId、connected（主实例的协议适配器是否已连接），以及会话监控的 session 明细。
 *
 * <p>投递停滞时整体置为 DOWN 并附 sessionError——2026-10-28 的故障中连接一切正常而消息不再流动，
 * 只看 connected 是发现不了的。备实例不轮询会话，状态恒为 UP。
 */
public class LeaderHealthIndicator implements HealthIndicator {

    private final LeaderElection election;
    private final SessionMonitor monitor;

    /** monitor 为 null 表示会话监控已关闭，此时行为与引入监控之前一致。 */
    public LeaderHealthIndicator(LeaderElection election, SessionMonitor monitor) {
        this.election = election;
        this.monitor = monitor;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        if (monitor != null) {
            SessionMonitor.Verdict verdict = monitor.verdict();
            if (!verdict.healthy()) {
                builder = Health.down().withDetail("sessionError", verdict.reason());
            }
            builder.withDetail("session", verdict.details());
        }
        return builder
                .withDetail("role", election.role().name())
                .withDetail("instanceId", election.instanceId())
                .withDetail("connected", election.adaptersConnected())
                .build();
    }
}
```

同步更新构造该 Bean 的配置类。**构造器签名由 1 个参数变为 2 个**，既有的 `LeaderHealthIndicator` 单元测试与配置类都会编译失败，一并更新：测试中传 `null` 表示监控关闭，另补一个用例断言「监控判定停滞时健康为 DOWN 且 details 含 sessionError」。

- [ ] **Step 5: application.yml**

`conduit.transport` 下追加：

```yaml
    # 经 EMQX REST API 观察自己的会话：丢弃、队列水位、会话进程内存与投递停滞。
    # 2026-10-28 定位的楔死故障中所有既有信号都是绿的，这里是唯一能发现它的地方。
    monitor:
      enabled: ${TRANSPORT_MONITOR_ENABLED:true}
      base-url: ${CONDUIT_TRANSPORT_MONITOR_BASE_URL:http://localhost:18083}
      # EMQX 5 的 REST API 只认 API key/secret，不接受控制台账号密码
      api-key: ${CONDUIT_TRANSPORT_MONITOR_API_KEY:}
      api-secret: ${CONDUIT_TRANSPORT_MONITOR_API_SECRET:}
      interval: 30s
      # 连续 6 次（约 3 分钟）投递总数不变才判定停滞，避免低流量时误报
      stall-samples: 6
      # 须与编排中 EMQX_CONNECTION_MAX_HEAP_SIZE 保持一致
      heap-limit: 256MB
```

- [ ] **Step 6: 编排下发 API key**

新建 `deploy/dev/emqx/api-key.bootstrap.tpl`，内容一行：

```
__EMQX_API_KEY__:__EMQX_API_SECRET__:viewer
```

在 EMQX 服务的启动命令中，按现有 `base.hocon.tpl` 的同样方式用 `sed` 渲染到 `/opt/emqx/data/api-key.bootstrap`（模板以只读挂载到 `/conduit/api-key.bootstrap.tpl`），并增加环境变量：

```yaml
      EMQX_API_KEY__BOOTSTRAP_FILE: /opt/emqx/data/api-key.bootstrap
      EMQX_API_KEY: ${EMQX_API_KEY:?EMQX_API_KEY is required}
      EMQX_API_SECRET: ${EMQX_API_SECRET:?EMQX_API_SECRET is required}
```

`deploy/standalone/docker-compose.yml` 的两个 transport 服务（YAML 锚点）增加：

```yaml
      CONDUIT_TRANSPORT_MONITOR_BASE_URL: http://emqx:18083
      CONDUIT_TRANSPORT_MONITOR_API_KEY: ${EMQX_API_KEY:?EMQX_API_KEY is required}
      CONDUIT_TRANSPORT_MONITOR_API_SECRET: ${EMQX_API_SECRET:?EMQX_API_SECRET is required}
```

两个 `.env.example` 的 EMQX 组增加（只给占位值）：

```
# transport 读取会话指标用的只读 API key（EMQX 5 的 REST API 不接受控制台账号密码）
EMQX_API_KEY=change-me-emqx-api-key                        # 必须改
EMQX_API_SECRET=change-me-emqx-api-secret                  # 必须改
```

- [ ] **Step 7: 跑 transport 全量测试**

Run: `mvn -B -o test -pl conduit-transport`

Expected: PASS。

- [ ] **Step 8: 提交**

```bash
git add conduit-transport/ deploy/
git commit -m "feat(transport): 经 EMQX REST API 轮询会话并接入健康检查"
```

---

### Task 5: e2e 场景与压测复验

**Files:**
- Modify: `conduit-e2e/src/test/java/dev/conduit/e2e/ReliabilityE2EIT.java`
- Create: `docs/test-reports/2026-10-28-session-wedge-fix-verification.md`

**Interfaces:**
- Consumes: Task 1–4b 的全部变更
- Produces: 无

**背景：** 关闭磁盘持久会话后，**「重启 EMQX」场景的语义变了**——EMQX 重启不再保留未投递的消息。该场景原来断言「不丢」，现在只有带消息 id 的设备经重发才不丢。

- [ ] **Step 1: 调整「重启 EMQX」场景**

把该场景的设备改为带 `id` 的可靠上报设备（与第五个场景同款），断言保持「计数恰好、无死信、无待确认」。在方法 Javadoc 中写明：

```java
    /**
     * 重启 EMQX：内存持久会话不保留未投递消息，所以本场景用带 id 的可靠上报设备——
     * 不丢由设备侧重发保证，而不是由 broker 的持久化保证（磁盘持久会话已于 2026-10-28 关闭）。
     * 不带 id 的设备在本场景下会丢消息，这是设备接入契约中明确声明的缺口。
     */
```

同步更新该类中 Testcontainers 启动 EMQX 时的环境变量，与 Task 3 的编排一致：`EMQX_DURABLE_SESSIONS__ENABLE=false`、`EMQX_MQTT__MAX_MQUEUE_LEN=100000`。

- [ ] **Step 2: 跑 e2e**

```bash
mvn -B -DskipTests install
mvn -B verify -Pe2e -pl conduit-e2e
```

Expected: 五个场景全部通过。失败必须定位，**不得降低断言**。

- [ ] **Step 3: 全量回归**

Run: `mvn -B -o verify`

Expected: BUILD SUCCESS。记录用例总数并与基线 699 对比。

- [ ] **Step 4: 压测复验**

在压测机复跑 3000 条/秒 × 180 秒，断言并记录：

| 断言 | 取数方式 |
|---|---|
| 无投递停滞（`send_msg` 持续增长） | EMQX REST API |
| 停压后 `mqueue_len` 与 `inflight_cnt` 归零 | EMQX REST API |
| EMQX 超堆被杀 0 次 | `docker logs conduit-emqx-1 2>&1 \| grep -c 'maximum heap size'`（加压前取基线） |
| mqueue 满载时 `heap_size × 8` 与 384MB 的余量 | EMQX REST API；**余量不足则下调 `EMQX_MAX_MQUEUE_LEN`，不得上调堆限** |
| 微批前后的持续投递速率（基线约 1,230 条/秒） | `send_msg` 增速 |

- [ ] **Step 5: 写测试报告**

新建 `docs/test-reports/2026-10-28-session-wedge-fix-verification.md`，按项目约定记录被测范围与代码状态、环境、实际命令、用例数/结果/耗时、跳过或阻塞项、非阻塞警告及日志位置。**失败和无效尝试也要如实记录。**

- [ ] **Step 6: 提交**

```bash
git add conduit-e2e/ docs/test-reports/2026-10-28-session-wedge-fix-verification.md
git commit -m "test(e2e): 按内存持久会话调整重启 EMQX 场景"
```

---

### Task 6: 文档与架构同步

**Files:**
- Modify: `CLAUDE.md`、`README.md`、`docs/roadmap-saas.md`
- Modify: `docs/images/architecture.svg`、`docs/images/reliable-uplink.svg`
- Modify: `docs/superpowers/specs/2026-10-16-conduit-v0.2r-reliability-design.md`、`docs/superpowers/specs/2026-10-20-conduit-reliable-uplink-design.md`
- Modify: 设备接入契约文档（上行报文格式一节）

**Interfaces:**
- Consumes: Task 1–5 的最终实现
- Produces: 无

- [ ] **Step 1: CLAUDE.md 正文**

1.2 节需要改写的位置：

1. 「transport 单活多备」→「会话与订阅」：删除/改写磁盘持久会话的描述，改为内存持久会话 + mqueue 定容（保留「EMQX 5.8.6 的磁盘持久会话拒绝共享订阅」这段历史说明，但注明磁盘持久会话已关闭）。
2. 「投递语义」：把「EMQX 开启磁盘持久会话，transport 离线期间的消息存 EMQX 磁盘（保留 1 天）」改写为内存队列及其容量含义。
3. 「端到端可靠上报」：补一句「不带 `id` 的设备在 EMQX 重启或队列溢出时会丢失消息」。
4. 「部署」：EMQX 磁盘持久会话相关说明改写。
5. 新增一条说明 transport 微批写总线与会话监控。

- [ ] **Step 2: CLAUDE.md 第 4 节追加决定记录**

```
- 2026-10-28 关闭 EMQX 磁盘持久会话：压测证明它在积压下会静默楔死——inflight 占满 32、投递永久停止、
  所有既有信号为绿，重连触发 DS 重放超堆被杀形成死循环，恢复只能丢弃会话与积压（实测滞留约 29 万条）。
  对比实验（唯一变量 durable_sessions.enable）下内存持久会话投递 307,263 条并完全排空、超堆被杀 0 次，
  失败模式由「静默、整批、必须人工」变为「可见、有界、自愈」。随之：mqueue 按主备切换窗口定容
  （EMQX_MAX_MQUEUE_LEN 默认 100000，与单连接堆限共享上限，调大前须确认 heap_size 余量）；
  transport 微批写入总线提高持续投递上限（原每条一次 XADD，实测约 1230 条/秒）；transport 主实例经
  EMQX REST API 轮询会话指标告警（该 API 只认 API key/secret，不接受控制台账号密码）。设备契约明确
  声明：不带 id 的上报只有逐跳 QoS 1 保证，EMQX 重启或队列溢出时会丢。
  mqtt.retry_interval 对持久会话无效，已实测证伪，不是可选解。
  （docs/superpowers/specs/2026-10-28-conduit-emqx-session-wedge-fix-design.md、
   docs/test-reports/2026-10-28-emqx-durable-session-wedge.md）
```

- [ ] **Step 3: 架构图**

`docs/images/architecture.svg` 与 `docs/images/reliable-uplink.svg` 中凡标注「磁盘持久会话」「持久会话（磁盘）」处改为「内存持久会话（队列 mqueue）」。改后自检文字、间距与连线，确认亮暗两种主题下均可读，并确认 Markdown 引用仍有效。

- [ ] **Step 4: README 与 roadmap**

`README.md` 架构说明中涉及 EMQX 持久化的表述同步改写。

`docs/roadmap-saas.md` 中 V0.3-F 的「评估是否去掉磁盘持久会话」条目改为已实施并链接本 spec；保留「升级 EMQX 6.x 用持久共享订阅去掉选主」作为未决项。

- [ ] **Step 5: 旧 spec 标注**

在 `2026-10-16-conduit-v0.2r-reliability-design.md` 与 `2026-10-20-conduit-reliable-uplink-design.md` 顶部各加一行：

```
> **已被取代（2026-10-28）**：其中「EMQX 磁盘持久会话」的决定已废止，改用内存持久会话，
> 见 `docs/superpowers/specs/2026-10-28-conduit-emqx-session-wedge-fix-design.md`。
```

- [ ] **Step 6: 设备接入契约**

在上行报文格式一节加入 spec 第 7 节的缺口声明（原文照录）：

> 不携带 `id` 的上报只有逐跳的 QoS 1 保证。在 EMQX 进程重启或崩溃时，已 PUBACK 但尚未投递给平台的消息会丢失；在接入速率持续超过平台投递能力时，超出 broker 队列容量的消息会被丢弃并计入 `dropped_msgs`。需要端到端不丢的设备必须携带 `id` 并实现本地持久化待确认队列，收到平台业务回执后才删除。

- [ ] **Step 7: 提交**

```bash
git add CLAUDE.md README.md docs/
git commit -m "docs: 同步内存持久会话的架构说明与设备契约声明"
```

---

## 完成后

- 交付说明中必须列出已更新的架构文件（`docs/images/architecture.svg`、`docs/images/reliable-uplink.svg`、`README.md`、`CLAUDE.md`）与测试报告链接。
- 压测机 192.168.126.129 上为实验所做的临时改动保留在 `deploy/dev/docker-compose.yml.durable-bak`；确认 Task 3 的编排变更已覆盖后删除该备份。
