package dev.conduit.common.bus;

import dev.conduit.common.message.RawMessage;
import dev.conduit.common.message.RawMessageCodec;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link MessageBus} 的 Redis Streams 实现：上行流用 XADD/XREADGROUP/XACK，
 * 崩溃遗留消息用 XPENDING+XCLAIM 重新认领，失败消息写入独立的死信流。
 * 处于 transport（写入）与 server 流水线（消费）之间。
 */
public class RedisStreamMessageBus implements MessageBus {

    // Lettuce's generic execute() cannot decode integer replies, so XPENDING goes through a script.
    private static final DefaultRedisScript<List> PENDING_IDLE = new DefaultRedisScript<>(
            "return redis.call('XPENDING', KEYS[1], ARGV[1], 'IDLE', ARGV[2], '-', '+', ARGV[3])", List.class);

    private final StringRedisTemplate redis;
    private final MessageBusProperties props;

    public RedisStreamMessageBus(StringRedisTemplate redis, MessageBusProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** XADD 到上行流，按 maxLen 近似裁剪（超长时最旧消息会被丢弃）。 */
    @Override
    public String publish(RawMessage message) {
        return xadd(props.streamKey(), RawMessageCodec.toFields(message), props.maxLen());
    }

    /** XGROUP CREATE（从 0-0 起、MKSTREAM），忽略 BUSYGROUP（组已存在），其他错误原样抛出。 */
    @Override
    public void ensureGroup(String group) {
        byte[] key = bytes(props.streamKey());
        try {
            redis.execute((RedisCallback<String>) c ->
                    c.streamCommands().xGroupCreate(key, group, ReadOffset.from("0-0"), true));
        } catch (RedisSystemException e) {
            String msg = String.valueOf(e.getMostSpecificCause().getMessage());
            if (!msg.contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    /** count 为非正数（约定用 -1）时不设 COUNT，一次取走当前可读的全部消息，即批大小「不限」。 */
    private static StreamReadOptions readOptions(int count, Duration block) {
        StreamReadOptions options = StreamReadOptions.empty().block(block);
        return count > 0 ? options.count(count) : options;
    }

    /** XREADGROUP 读取组内尚未投递的新消息（lastConsumed），deliveryCount 固定为 1。 */
    @Override
    public List<Delivery> read(String group, String consumer, int count, Duration block) {
        @SuppressWarnings("unchecked")
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(group, consumer),
                readOptions(count, block),
                StreamOffset.create(props.streamKey(), ReadOffset.lastConsumed()));
        if (records == null) {
            return List.of();
        }
        return records.stream()
                .map(r -> new Delivery(r.getId().getValue(), toStringMap(r.getValue()), 1))
                .toList();
    }

    /**
     * 先用 XPENDING IDLE 在服务端筛出空闲超时的条目并取得既有投递次数，再 XCLAIM 转给当前消费者；
     * deliveryCount = 既有次数 + 1。
     */
    @Override
    public List<Delivery> claimStale(String group, String consumer, Duration minIdle, int count) {
        // IDLE filter runs server-side so the count window only holds genuinely stale entries
        // (Spring Data Redis 3.5 XPendingOptions has no IDLE support, hence the raw command).
        List<Object[]> stale = pendingIdle(group, minIdle, count);
        if (stale.isEmpty()) {
            return List.of();
        }
        Map<String, Long> previousCounts = new LinkedHashMap<>();
        stale.forEach(e -> previousCounts.put((String) e[0], (Long) e[1]));

        List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(
                props.streamKey(), group, consumer, minIdle,
                previousCounts.keySet().stream().map(RecordId::of).toArray(RecordId[]::new));

        return claimed.stream()
                .map(r -> {
                    String id = r.getId().getValue();
                    return new Delivery(id, toStringMap(r.getValue()), previousCounts.getOrDefault(id, 0L) + 1);
                })
                .toList();
    }

    /** XPENDING key group IDLE ms - + count; returns {id, totalDeliveryCount} per entry. */
    private List<Object[]> pendingIdle(String group, Duration minIdle, int count) {
        List<?> rows = (List<?>) redis.execute(PENDING_IDLE, List.of(props.streamKey()),
                group, Long.toString(minIdle.toMillis()), Integer.toString(count));
        List<Object[]> result = new java.util.ArrayList<>();
        if (rows != null) {
            for (Object row : rows) {
                List<?> f = (List<?>) row; // [id, consumer, idleMs, deliveryCount]
                result.add(new Object[]{String.valueOf(f.get(0)), (Long) f.get(3)});
            }
        }
        return result;
    }

    /** XACK；ids 为空直接返回。 */
    @Override
    public void ack(String group, List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        redis.opsForStream().acknowledge(props.streamKey(), group, ids.toArray(String[]::new));
    }

    /** 先 recordDead 再 ack；顺序不可颠倒，避免写死信前崩溃导致消息丢失。 */
    @Override
    public void deadLetter(String group, Delivery delivery, String reason) {
        recordDead(delivery, reason, Map.of());
        ack(group, List.of(delivery.id()));
    }

    /** 在原始字段上叠加 details 与标准字段（reason 等，不可被 details 覆盖）后写入死信流。 */
    @Override
    public void recordDead(Delivery delivery, String reason, Map<String, String> details) {
        Map<String, String> fields = new LinkedHashMap<>(delivery.fields());
        fields.putAll(details);
        // 标准字段最后写入，details 不能覆盖
        fields.put("reason", reason);
        fields.put("originalId", delivery.id());
        fields.put("deliveryCount", Long.toString(delivery.deliveryCount()));
        fields.put("failedAt", Long.toString(System.currentTimeMillis()));
        xadd(props.deadKey(), fields, props.deadMaxLen());
    }

    /** XRANGE 上行流 [startId, endId]，最多 count 条（count 必须大于 0，否则抛 IllegalArgumentException）；不经过消费组，deliveryCount 固定为 0。 */
    @Override
    public List<Delivery> range(String startId, String endId, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count 必须大于 0，实际为 " + count);
        }
        List<MapRecord<String, Object, Object>> records = redis.opsForStream()
                .range(props.streamKey(), Range.closed(startId, endId), Limit.limit().count(count));
        if (records == null) {
            return List.of();
        }
        return records.stream()
                .map(r -> new Delivery(r.getId().getValue(), toStringMap(r.getValue()), 0))
                .toList();
    }

    /** 以字节方式 XADD 并近似裁剪到 maxLen；Redis 未返回 id 时抛 IllegalStateException。 */
    private String xadd(String key, Map<String, String> fields, long maxLen) {
        Map<byte[], byte[]> raw = new LinkedHashMap<>();
        fields.forEach((k, v) -> raw.put(bytes(k), bytes(v)));
        RecordId id = redis.execute((RedisCallback<RecordId>) c -> c.streamCommands().xAdd(
                StreamRecords.rawBytes(raw).withStreamKey(bytes(key)),
                XAddOptions.maxlen(maxLen).approximateTrimming(true)));
        if (id == null) {
            throw new IllegalStateException("XADD returned no id for " + key);
        }
        return id.getValue();
    }

    private static Map<String, String> toStringMap(Map<Object, Object> value) {
        Map<String, String> result = new LinkedHashMap<>();
        value.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
        return result;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
