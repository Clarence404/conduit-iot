package dev.conduit.transport.mqtt;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext;
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5ClientBuilder;
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5DisconnectException;
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAck;
import com.hivemq.client.mqtt.mqtt5.message.disconnect.Mqtt5DisconnectReasonCode;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5RetainHandling;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.suback.Mqtt5SubAck;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.suback.Mqtt5SubAckReasonCode;
import com.hivemq.client.mqtt.mqtt5.message.unsubscribe.unsuback.Mqtt5UnsubAckReasonCode;
import com.hivemq.client.mqtt.mqtt5.message.unsubscribe.unsuback.Mqtt5UnsubAck;
import dev.conduit.common.bus.MessageBus;
import dev.conduit.common.bus.PublishResult;
import dev.conduit.common.message.MessageType;
import dev.conduit.common.message.RawMessage;
import dev.conduit.transport.adapter.ProtocolAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * MQTT 协议适配器：以固定 clientId、cleanStart=false 的会话普通订阅设备上行 topic 与 EMQX 的 $SYS 上下线 topic，
 * 攒批 XADD 成功后才回 PUBACK（手动 ACK），保证至少一次投递；属性报文带 id 时还向设备发送“平台已持久接收”的业务回执。
 * EMQX 使用内存持久会话（磁盘持久会话已关闭，原因见 CLAUDE.md 决定记录）：transport 离线期间的消息进入内存队列 mqueue
 * （容量有限，溢出按 mqueue_dropped 计数），下次 start（本实例或接管的新主实例）时补发；EMQX 重启则不保留未投递消息，
 * 端到端不丢由设备侧消息 id + 平台业务回执负责。
 *
 * <p>每次 start 打开一个独立的 {@link Session}（客户端、投递线程、状态标志），stop 关闭它，可反复调用。
 * 不使用 HiveMQ 自动重连：意外断线或会话被接管时置 needsRestart，由选主协调器确认仍持锁后重新 start。
 *
 * <p>一个实例对应主实例的一条连接。配置 connections > 1 时主实例创建多个实例、改用共享订阅分摊投递，
 * 第 index 条的 clientId 由 {@link MqttTransportProperties#clientIdOf} 给出；上下线事件只由第 0 条订阅；
 * 第 0 条在共享模式下还会退订旧版留下的非共享过滤器（见 {@link #retireLegacySubscription}）。
 */
public class MqttProtocolAdapter implements ProtocolAdapter {

    static final String DEVICE_TOPIC_FILTER = "conduit/+/+/up/#";
    static final String CONNECTED_TOPIC_FILTER = "$SYS/brokers/+/clients/+/connected";
    static final String DISCONNECTED_TOPIC_FILTER = "$SYS/brokers/+/clients/+/disconnected";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration SUBSCRIBE_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(MqttProtocolAdapter.class);
    private static final Duration DISCONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(30);
    private static final long MISSING_TS_WARN_INTERVAL_MS = Duration.ofMinutes(1).toMillis();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MqttTransportProperties props;
    private final MessageBus bus;
    private final Clock clock;
    private final Duration initialBackoff;
    /** 本实例是主实例的第几条连接（从 0 开始）。 */
    private final int index;
    /** 本条连接的 clientId，见 {@link MqttTransportProperties#clientIdOf}。 */
    private final String clientId;
    private final AtomicReference<Session> current = new AtomicReference<>();
    private final ConcurrentHashMap<String, Long> missingTimestampWarnings = new ConcurrentHashMap<>();
    /** 延迟提交的触发器：只负责把 flush 投回会话的投递线程，自身不做业务。 */
    private final ScheduledExecutorService flushTimer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("mqtt-flush-timer").daemon().factory());

    /** 一次 start 到 stop 之间的连接及其资源；停止后即作废，不再复用。 */
    final class Session {
        // 单线程串行处理：保序，且阻塞重试不会卡住 Netty 事件循环
        final ExecutorService deliveryExecutor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("mqtt-delivery").daemon().factory());
        /** 本批待提交的条目，只在 deliveryExecutor 这一条线程上读写，无需加锁；会话作废后随对象一起回收。 */
        final List<Pending> pending = new ArrayList<>();
        volatile Mqtt5AsyncClient client;
        volatile boolean stopping;
        volatile boolean needsRestart;
        /** 连接成功且全部订阅都已被 broker 接受（SUBACK 通过 requireGranted）后才置位；断开时清除。 */
        volatile boolean ready;
        /** 连接请求的 future：HiveMQ 不会因超时或断开而取消它，关闭时需挂回调丢弃迟到的 CONNACK。 */
        volatile CompletableFuture<Mqtt5ConnAck> connectFuture;
        /** 会话关闭时完成，用来唤醒正在等待连接或订阅的 start。 */
        final CompletableFuture<Void> stopSignal = new CompletableFuture<>();

        /** 是否已就绪：订阅已授予且连接仍在（只有连上而未订阅成功时不算）。 */
        boolean connected() {
            Mqtt5AsyncClient c = client;
            return ready && c != null && c.getState().isConnected();
        }
    }

    /** 一条已解析、等待随批提交到总线的消息；提交成功后才 ACK 并发业务回执。 */
    private record Pending(Mqtt5Publish publish, ParsedTopic topic, RawMessage message) {
    }

    /** 第 0 条连接（单连接部署与测试沿用）。 */
    public MqttProtocolAdapter(MqttTransportProperties props, MessageBus bus, Clock clock) {
        this(props, bus, clock, 0);
    }

    /** 主实例的第 index 条连接。 */
    public MqttProtocolAdapter(MqttTransportProperties props, MessageBus bus, Clock clock, int index) {
        this(props, bus, clock, Duration.ofSeconds(1), index);
    }

    MqttProtocolAdapter(MqttTransportProperties props, MessageBus bus, Clock clock, Duration initialBackoff) {
        this(props, bus, clock, initialBackoff, 0);
    }

    MqttProtocolAdapter(MqttTransportProperties props, MessageBus bus, Clock clock, Duration initialBackoff,
                        int index) {
        if (index < 0 || index >= props.connections()) {
            throw new IllegalArgumentException("connection index " + index + " out of range [0, "
                    + props.connections() + ")");
        }
        this.props = props;
        this.bus = bus;
        this.clock = clock;
        this.initialBackoff = initialBackoff;
        this.index = index;
        this.clientId = props.clientIdOf(index);
    }

    /** 选主日志里的适配器名；多连接时带序号，便于看出是哪一条断了。 */
    @Override
    public String name() {
        return props.shared() ? "mqtt-" + index : "mqtt";
    }

    /** 本条连接的 clientId。 */
    public String clientId() {
        return clientId;
    }

    /** 设备上行的订阅过滤器：共享模式下加 $share/{组}/ 前缀，由 EMQX 在各连接间分摊。 */
    String deviceTopicFilter() {
        return props.shared() ? "$share/" + props.sharedGroup() + "/" + DEVICE_TOPIC_FILTER : DEVICE_TOPIC_FILTER;
    }

    /** 是否订阅上下线事件：只由第 0 条订阅，否则每条连接各收一份、重复 K 次。 */
    boolean subscribesPresence() {
        return index == 0;
    }

    /**
     * 关闭上一次的连接（若有），新建客户端并连接 EMQX（MQTT 5，cleanStart=false，会话保留 sessionExpirySeconds），
     * 再同步订阅并检查 SUBACK。先注册全局回调再连接：恢复持久会话后 broker 会立即补发积压消息。
     *
     * @param stillValid 启动是否仍有效；登记会话后（发出 CONNECT 前）与订阅前各检查一次，失效则放弃
     * @throws IllegalStateException 连接或订阅失败、超时、被中断、启动已失效或被并发 stop；会话已清理
     */
    @Override
    public void start(BooleanSupplier stillValid) {
        Session s = register();
        if (!stillValid.getAsBoolean()) {
            abandon(s);
            throw new IllegalStateException("MQTT adapter start is no longer valid");
        }
        Mqtt5ClientBuilder builder = MqttClient.builder().useMqttVersion5()
                .identifier(clientId)
                .serverHost(props.host())
                .serverPort(props.port())
                .addDisconnectedListener(context -> onDisconnected(s, context));
        if (props.username() != null && !props.username().isBlank()) {
            byte[] password = props.password() == null
                    ? new byte[0] : props.password().getBytes(StandardCharsets.UTF_8);
            builder = builder.simpleAuth().username(props.username()).password(password).applySimpleAuth();
        }
        Mqtt5AsyncClient client = buildClient(builder);
        s.client = client;
        // ALL 而非 SUBSCRIBED：新客户端对象不知道持久会话里已有的订阅，恢复会话后补发的消息也必须走写总线再 ACK 的路径
        client.publishes(MqttGlobalPublishFilter.ALL, p -> onPublish(s, p), s.deliveryExecutor, true);

        try {
            if (s.stopping) {
                throw new IllegalStateException("MQTT adapter was stopped before connecting");
            }
            CompletableFuture<Mqtt5ConnAck> connectFuture = connect(client);
            s.connectFuture = connectFuture;
            // 与 stop 的竞态：stop 可能在登记 connectFuture 之前已执行 close，这里补挂丢弃回调
            if (s.stopping) {
                dropLateConnection(s);
                throw new IllegalStateException("MQTT adapter was stopped while connecting");
            }
            await(s, connectFuture, CONNECT_TIMEOUT);
            if (!stillValid.getAsBoolean()) {
                throw new IllegalStateException("MQTT adapter start is no longer valid");
            }
            requireGranted(await(s, subscribe(client), SUBSCRIBE_TIMEOUT));
            retireLegacySubscription(s, client);
            s.ready = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abandon(s);
            throw new IllegalStateException("Interrupted while connecting to MQTT " + endpoint(), e);
        } catch (ExecutionException e) {
            abandon(s);
            throw new IllegalStateException("MQTT connect/subscribe to " + endpoint() + " failed: "
                    + describe(e.getCause()), e.getCause());
        } catch (TimeoutException e) {
            abandon(s);
            throw new IllegalStateException("MQTT connect/subscribe to " + endpoint() + " timed out", e);
        } catch (RuntimeException e) {
            abandon(s);
            throw new IllegalStateException("MQTT connect/subscribe to " + endpoint() + " failed: "
                    + describe(e), e);
        }
        if (s.stopping) {
            abandon(s);
            throw new IllegalStateException("MQTT adapter was stopped while connecting to " + endpoint());
        }
        log.info("MQTT connected to {} as {} (session expiry {} s), subscribed to {}{}", endpoint(), clientId,
                props.sessionExpirySeconds(), deviceTopicFilter(), subscribesPresence() ? " and presence events" : "");
    }

    /** 停止当前连接：置停止标志、有界等待断开、关闭投递线程；未 ACK 的消息留在 EMQX 会话中，下次 start 时重投。 */
    @Override
    public void stop() {
        Session s = current.getAndSet(null);
        if (s != null) {
            close(s);
            log.info("MQTT adapter stopped ({} as {})", endpoint(), clientId);
        }
    }

    @Override
    public boolean isConnected() {
        Session s = current.get();
        return s != null && !s.stopping && s.connected();
    }

    @Override
    public boolean needsRestart() {
        Session s = current.get();
        return s != null && s.needsRestart;
    }

    /** 当前登记的会话（测试接缝）。 */
    Session currentSession() {
        return current.get();
    }

    /** 新建并登记当前会话（测试接缝：单元测试不连接 broker，直接用会话驱动 onPublish / onDisconnected）。 */
    Session openSession() {
        return register();
    }

    /** 原子地登记新会话并关闭被替换的旧会话：并发的 stop 因此一定能关到新会话，两个并发 start 也不会泄漏会话。 */
    private Session register() {
        Session s = new Session();
        Session prev = current.getAndSet(s);
        if (prev != null) {
            close(prev);
        }
        return s;
    }

    /** 创建 MQTT 客户端（测试接缝：单元测试可返回模拟客户端而不连接 broker）。 */
    Mqtt5AsyncClient buildClient(Mqtt5ClientBuilder builder) {
        return builder.buildAsync();
    }

    /** 发起连接（MQTT 5，cleanStart=false，会话保留 sessionExpirySeconds；测试接缝）。 */
    CompletableFuture<Mqtt5ConnAck> connect(Mqtt5AsyncClient client) {
        return client.connectWith()
                .cleanStart(false)
                .sessionExpiryInterval(props.sessionExpirySeconds())
                .send();
    }

    /**
     * 等待 future 完成，但会话被 stop 时立即醒来。
     *
     * @throws IllegalStateException 等待期间会话已被停止
     */
    private <T> T await(Session s, CompletableFuture<T> future, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture.anyOf(future, s.stopSignal).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (s.stopping) {
            throw new IllegalStateException("MQTT adapter was stopped while connecting to " + endpoint());
        }
        return future.get();
    }

    /**
     * 给连接 future 挂回调：CONNACK 迟到（超时或停止之后才成功）时立即断开，
     * 否则这个僵尸连接会以共用的 clientId 顶掉主实例的会话。此时 stopping 已置位，onDisconnected 会忽略它。
     */
    private void dropLateConnection(Session s) {
        CompletableFuture<Mqtt5ConnAck> f = s.connectFuture;
        Mqtt5AsyncClient c = s.client;
        // 已完成的 future 说明连接结果已定：成功的由 close 的有界断开处理，失败的无需处理，这里只管仍在等待的
        if (f != null && c != null && !f.isDone()) {
            f.whenComplete((ack, err) -> {
                if (err == null) {
                    try {
                        c.disconnect().whenComplete((r, e) -> {
                            if (e != null) {
                                log.debug("Dropping late MQTT connection failed: {}", describe(e));
                            }
                        });
                    } catch (RuntimeException e) {
                        log.debug("Dropping late MQTT connection failed: {}", describe(e));
                    }
                }
            });
        }
    }

    /** start 失败时作废会话：若仍是当前会话则清除登记，再关闭。 */
    private void abandon(Session s) {
        current.compareAndSet(s, null);
        close(s);
    }

    /** 关闭会话，可重复调用：断开失败只记 DEBUG（broker 已不可达时属正常）。 */
    private void close(Session s) {
        s.stopping = true;
        s.stopSignal.complete(null);
        dropLateConnection(s);
        Mqtt5AsyncClient c = s.client;
        if (c != null) {
            try {
                c.disconnect().get(DISCONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                log.debug("MQTT disconnect did not complete cleanly: {}", describe(e));
            }
        }
        s.deliveryExecutor.shutdownNow();
        try {
            if (!s.deliveryExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("MQTT delivery thread did not terminate within 2 s after stop");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 连接断开回调（在事件循环上，只设标志与打日志）。本适配器主动断开或会话已停止时忽略；
     * 其余情况（broker 重启、网络断开、会话被同 clientId 的新连接接管）置 needsRestart，由协调器决定是否重连。
     */
    void onDisconnected(Session s, MqttClientDisconnectedContext context) {
        s.ready = false;
        if (s.stopping || context.getSource() == MqttDisconnectSource.USER) {
            log.debug("MQTT disconnected from {} by this adapter", endpoint());
            return;
        }
        s.needsRestart = true;
        Throwable cause = context.getCause();
        if (isSessionTakenOver(cause)) {
            log.warn("MQTT session {} on {} was taken over by another connection with the same client id",
                    clientId, endpoint());
        } else {
            log.warn("MQTT connection to {} lost (source={}): {}", endpoint(), context.getSource(),
                    cause == null ? "no cause" : cause.getMessage());
        }
    }

    /** 断开原因是否为「会话被接管」（EMQX 收到同 clientId 的新连接，DISCONNECT 原因码 0x8E）。 */
    static boolean isSessionTakenOver(Throwable cause) {
        return cause instanceof Mqtt5DisconnectException e
                && e.getMqttMessage().getReasonCode() == Mqtt5DisconnectReasonCode.SESSION_TAKEN_OVER;
    }

    /**
     * SUBACK 中任一订阅被拒（错误码）即视为订阅失败。HiveMQ 只在全部被拒时才让 future 异常完成，部分被拒需在此检查。
     *
     * @throws IllegalStateException 含错误码，消息中列出全部原因码
     */
    static void requireGranted(Mqtt5SubAck ack) {
        List<Mqtt5SubAckReasonCode> codes = ack.getReasonCodes();
        if (codes.stream().anyMatch(Mqtt5SubAckReasonCode::isError)) {
            throw new IllegalStateException("MQTT subscription rejected: " + codes);
        }
    }

    /**
     * 订阅设备上行 topic（多连接时为共享订阅，见 {@link #deviceTopicFilter}），第 0 条连接另订阅 EMQX 的上下线系统 topic，
     * 均为 QoS 1。设备 topic 不接收保留消息（DO_NOT_SEND）：否则每次重启都会以新的 receivedAt 重放，让离线设备复活。
     */
    CompletableFuture<Mqtt5SubAck> subscribe(Mqtt5AsyncClient client) {
        var builder = client.subscribeWith()
                .addSubscription().topicFilter(deviceTopicFilter()).qos(MqttQos.AT_LEAST_ONCE)
                .retainHandling(Mqtt5RetainHandling.DO_NOT_SEND).applySubscription();
        if (!subscribesPresence()) {
            return builder.send();
        }
        return builder
                .addSubscription().topicFilter(CONNECTED_TOPIC_FILTER).qos(MqttQos.AT_LEAST_ONCE).applySubscription()
                .addSubscription().topicFilter(DISCONNECTED_TOPIC_FILTER).qos(MqttQos.AT_LEAST_ONCE)
                .applySubscription()
                .send();
    }

    /**
     * 共享模式下由第 0 条连接退订旧版留下的非共享过滤器 {@link #DEVICE_TOPIC_FILTER}。
     *
     * <p>为什么需要：升级前的单连接部署在 EMQX 里留有 clientId 为 {@code conduit-transport}、挂着该非共享订阅的
     * 持久会话（保留 24 小时）。第 0 条沿用这个 clientId 接管它，于是会话里排队的消息照常补发、不丢；
     * 但若不退订，这条连接会继续通过非共享订阅收到<b>全部</b>上行，等于又回到单连接扛全量，共享订阅形同虚设。
     * 退订只影响此后新消息的路由，已在会话队列里的消息照常投递。
     *
     * <p>全新安装时没有这条订阅，EMQX 回 NO_SUBSCRIPTIONS_EXISTED，视为成功。其他原因码视为失败并抛出，
     * 由选主协调器重试——宁可连不上，也不能带着一条全量的非共享订阅静默运行。
     */
    private void retireLegacySubscription(Session s, Mqtt5AsyncClient client)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (!props.shared() || index != 0) {
            return;
        }
        Mqtt5UnsubAck ack = await(s, client.unsubscribeWith().topicFilter(DEVICE_TOPIC_FILTER).send(),
                SUBSCRIBE_TIMEOUT);
        Mqtt5UnsubAckReasonCode code = ack.getReasonCodes().get(0);
        if (code == Mqtt5UnsubAckReasonCode.SUCCESS) {
            log.info("Retired legacy non-shared subscription {} on {}", DEVICE_TOPIC_FILTER, clientId);
        } else if (code != Mqtt5UnsubAckReasonCode.NO_SUBSCRIPTIONS_EXISTED) {
            throw new IllegalStateException("Failed to retire legacy subscription " + DEVICE_TOPIC_FILTER
                    + " on " + clientId + ": " + code);
        }
    }

    /**
     * 处理一条收到的 MQTT 消息（在会话的单线程投递线程上串行执行，保序）。
     * 无法路由的 topic 与平台自身客户端（ownClientPrefix）的上下线事件直接 ACK 丢弃；
     * 其余组装为 RawMessage 入队攒批：满 batchSize 立即提交，否则最迟 batchDelay 后提交；
     * 批写入总线成功后才逐条 ACK（并发业务回执），失败或会话停止时不 ACK。
     */
    void onPublish(Session s, Mqtt5Publish publish) {
        String topic = publish.getTopic().toString();
        Optional<ParsedTopic> parsed = TopicParser.parse(topic);
        if (parsed.isEmpty()) {
            log.warn("Dropping unroutable topic {}", topic);
            acknowledge(publish);
            return;
        }
        ParsedTopic t = parsed.get();
        if (isPresence(t.type()) && t.deviceKey().startsWith(props.ownClientPrefix())) {
            log.debug("Ignoring presence of platform client {}", t.deviceKey());
            acknowledge(publish);
            return;
        }
        RawMessage message = new RawMessage(t.productKey(), t.deviceKey(), t.type(),
                publish.getPayloadAsBytes(), clock.millis(), Map.of("topic", topic));
        if (isPresence(t.type())) {
            log.debug("MQTT presence event: clientId={} {}", t.deviceKey(),
                    t.type() == MessageType.PRESENCE_CONNECTED ? "connected" : "disconnected");
        } else {
            log.debug("MQTT received: topic={} productKey={} deviceKey={} type={} payloadBytes={}", topic,
                    t.productKey(), t.deviceKey(), t.type(), message.payload().length);
        }
        if (log.isTraceEnabled()) {
            log.trace("MQTT payload of {}: {}", t.deviceKey(), preview(message.payload()));
        }
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
     * 提交统一回到 deliveryExecutor 执行，保证与 onPublish 串行、不需要加锁；
     * 会话已停止（含执行器已关闭）时直接放弃。
     */
    private void scheduleFlush(Session s) {
        flushTimer.schedule(() -> {
            if (!s.stopping) {
                try {
                    s.deliveryExecutor.execute(() -> flush(s));
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    log.debug("Delayed flush skipped, session already closed");
                }
            }
        }, props.batchDelay().toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 提交当前批：反复提交尚未成功的条目直到全部写入总线，然后按到达顺序逐条发业务回执并 ACK。
     *
     * <p>绝不能留下未 ACK 的条目而继续处理后面的：EMQX 的 mqtt.retry_interval 默认为 infinity，
     * 已发出未 ACK 的在途消息不会重传，只在客户端重连时才重投；而 EMQX 的 max_inflight 默认为 32（本项目在编排中固化为 1024）。
     * 一条失败后放着不管就永久占住一个 inflight 槽位，攒满 32 条后 EMQX 不再投递，整条接入链路停摆。
     * 所以逐条失败也在此阻塞重试（与旧的逐条 publishWithRetry 语义一致）；会话停止时放弃且整批不 ACK。
     */
    private void flush(Session s) {
        if (s.pending.isEmpty() || s.stopping) {
            return;
        }
        List<Pending> batch = List.copyOf(s.pending);
        s.pending.clear();
        List<PublishResult> results = publishAllWithRetry(s, batch);
        if (results == null) {
            return; // 会话停止，一条都不 ACK
        }
        for (int i = 0; i < batch.size(); i++) {
            Pending p = batch.get(i);
            PublishResult r = results.get(i);
            log.debug("Published to bus: id={} deviceKey={} type={}",
                    r.id(), p.message().deviceKey(), p.message().type());
            // 回执必须在总线写入成功之后发，且先于 ACK；顺序与到达顺序一致，PUBACK 没有空洞
            if (p.topic().type() == MessageType.PROPERTY) {
                sendReliableAck(s, p.topic(), p.message());
            }
            acknowledge(p.publish());
        }
    }

    /**
     * 把整批写入总线并重试到全部成功：整体抛异常（下游不可用）与逐条失败都按退避重试，
     * 每轮只重新提交「尚未成功」的条目，已成功的不会重复写入。
     *
     * @return 与入参等长同序的成功结果；会话停止或线程被中断时返回 null
     */
    private List<PublishResult> publishAllWithRetry(Session s, List<Pending> batch) {
        PublishResult[] done = new PublishResult[batch.size()];
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            remaining.add(i);
        }
        for (int attempt = 0; !s.stopping; attempt++) {
            List<Integer> current = remaining;
            List<RawMessage> messages = current.stream().map(i -> batch.get(i).message()).toList();
            String failure;
            try {
                List<PublishResult> results = bus.publish(messages);
                List<Integer> stillFailing = new ArrayList<>();
                String lastError = null;
                for (int k = 0; k < current.size(); k++) {
                    PublishResult r = results.get(k);
                    if (r.succeeded()) {
                        done[current.get(k)] = r;
                    } else {
                        stillFailing.add(current.get(k));
                        lastError = r.failure().getMessage();
                    }
                }
                remaining = stillFailing;
                if (remaining.isEmpty()) {
                    return List.of(done);
                }
                failure = lastError;
            } catch (RuntimeException e) {
                failure = e.getMessage();
            }
            Duration delay = backoff(attempt);
            // 不能因失败而跳过 ACK（见 flush 注释：retry_interval=infinity 下失败条目会永久占住 inflight 槽位并耗尽窗口）
            log.warn("Bus publish of {} of {} messages failed (attempt {}), retrying in {} ms: {}",
                    remaining.size(), batch.size(), attempt + 1, delay.toMillis(), failure);
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * 总线持久写入成功后，为带有效 id 的属性上报发 QoS 0 业务回执。发送失败只记日志，设备会因收不到回执而重发。
     */
    private void sendReliableAck(Session s, ParsedTopic topic, RawMessage message) {
        Optional<ReliableUplink.Metadata> metadata = ReliableUplink.extract(message.payload());
        if (metadata.isEmpty()) {
            log.debug("Reliable uplink metadata absent or invalid for device {}, skipping ack", message.deviceKey());
            return;
        }
        ReliableUplink.Metadata reliable = metadata.get();
        if (!reliable.hasTimestamp()) {
            warnMissingTimestamp(message.deviceKey(), message.receivedAt());
        }
        String ackTopic = "conduit/%s/%s/down/ack".formatted(topic.productKey(), topic.deviceKey());
        byte[] payload;
        try {
            payload = JSON.writeValueAsBytes(Map.of("id", reliable.id(), "ts", message.receivedAt()));
        } catch (JsonProcessingException e) {
            log.warn("Could not encode reliable uplink ack for device {}: {}", message.deviceKey(), e.getMessage());
            return;
        }
        publishReliableAck(s, ackTopic, payload).whenComplete((ignored, error) -> {
            if (error != null) {
                log.warn("Reliable uplink ack send failed for device {}: {}", message.deviceKey(), describe(error));
            } else {
                log.debug("Reliable uplink ack sent: deviceKey={} id={}", message.deviceKey(), reliable.id());
            }
        });
    }

    /** MQTT 发布测试接缝；生产环境使用当前主会话连接发送 QoS 0 回执。 */
    CompletableFuture<?> publishReliableAck(Session s, String topic, byte[] payload) {
        Mqtt5AsyncClient client = s.client;
        if (client == null || !client.getState().isConnected()) {
            return CompletableFuture.failedFuture(new IllegalStateException("MQTT session is not connected"));
        }
        try {
            return client.publishWith().topic(topic).qos(MqttQos.AT_MOST_ONCE).payload(payload).send();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** 同一设备缺少 ts 的告警每分钟最多一条，避免故障设备持续上报时刷屏。 */
    private void warnMissingTimestamp(String deviceKey, long now) {
        missingTimestampWarnings.compute(deviceKey, (key, last) -> {
            if (last == null || now - last >= MISSING_TS_WARN_INTERVAL_MS || now < last) {
                log.warn("Reliable uplink from device {} has id but no ts; retries may create duplicate points",
                        deviceKey);
                return now;
            }
            return last;
        });
    }

    /** 取 payload 的前 256 个字符用于 TRACE 日志（按 UTF-8 解码，避免大报文刷屏）。 */
    private static String preview(byte[] payload) {
        String text = new String(payload, StandardCharsets.UTF_8);
        return text.length() <= 256 ? text : text.substring(0, 256) + "...(truncated)";
    }

    /** 指数退避：initialBackoff * 2^attempt（指数封顶 5），上限 30 秒。 */
    private Duration backoff(int attempt) {
        Duration delay = initialBackoff.multipliedBy(1L << Math.min(attempt, 5));
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
    }

    /** ACK 失败不能当作总线失败重投：消息已入总线，重复投递由 broker 重发（至少一次）。 */
    private void acknowledge(Mqtt5Publish publish) {
        try {
            publish.acknowledge();
        } catch (RuntimeException e) {
            log.warn("Failed to acknowledge MQTT publish {}: {}", publish.getTopic(), e.getMessage());
        }
    }

    /** 是否为设备上下线事件（而非业务数据）。 */
    private static boolean isPresence(MessageType type) {
        return type == MessageType.PRESENCE_CONNECTED || type == MessageType.PRESENCE_DISCONNECTED;
    }

    private String endpoint() {
        return props.host() + ":" + props.port();
    }

    private static String describe(Throwable e) {
        return e == null ? "unknown" : e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
