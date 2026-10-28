package dev.conduit.common.bus;

import dev.conduit.common.message.RawMessage;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 接入与处理之间的消息总线。V0.1 由 Redis Streams 实现，后续可替换为 Kafka / RabbitMQ。
 * 语义为至少一次投递：消费者处理成功后必须 ack，否则消息会被重新认领。
 *
 * <p>契约：
 * <ul>
 *   <li>{@link #read} 首次投递的 deliveryCount 为 1；阻塞超时返回空列表。{@code block} 必须小于 Redis
 *       客户端命令超时，否则阻塞读会被客户端当作超时报错。</li>
 *   <li>{@link #claimStale} 在服务端按空闲时间过滤，返回的 deliveryCount 为此前投递次数 + 1。
 *       已被 MAXLEN 裁剪掉的条目不会再出现在结果中（见设计文档 9.3 已知限制）。</li>
 *   <li>{@link #deadLetter} 先写死信流再 ack 原消息；两步之间崩溃会产生重复死信，属于至少一次语义可接受。</li>
 *   <li>{@link #recordDead} 只写死信流、不确认原消息，用于原消息已部分处理成功、仍需留痕的情况。</li>
 *   <li>{@link #range} 只读、不进消费组、不改变待确认列表，返回的 deliveryCount 为 0。</li>
 * </ul>
 */
public interface MessageBus {

    /** 发布一条原始消息到上行流（传输层调用）。返回总线分配的消息 id。 */
    String publish(RawMessage message);

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

    /** 确保消费组存在（幂等；组已存在时静默返回）。流不存在时会一并创建。 */
    void ensureGroup(String group);

    /** 以消费者身份读取新消息，最多 count 条，最长阻塞 block；无新消息返回空列表。 */
    List<Delivery> read(String group, String consumer, int count, Duration block);

    /** 认领空闲超过 minIdle 的未确认消息（崩溃或处理超时的遗留），用于重试；最多 count 条。 */
    List<Delivery> claimStale(String group, String consumer, Duration minIdle, int count);

    /** 确认消息已处理完成；ids 为空时不做任何事。 */
    void ack(String group, List<String> ids);

    /** 将消息送入死信流并确认原消息，使其不再被重新认领。reason 记录失败原因。 */
    void deadLetter(String group, Delivery delivery, String reason);

    /** 写一条死信记录（原始字段 + details + reason/originalId/deliveryCount/failedAt），不 ack。 */
    void recordDead(Delivery delivery, String reason, Map<String, String> details);

    /**
     * 按条目 id 区间读取上行流的原始条目（XRANGE，两端均含），最多 count 条，按 id 升序返回。
     * 用于遥测重放：Redis 流条目 id 的前半段就是毫秒时间戳，所以时间范围直接对应 id 范围。
     * 不经过消费组，不影响待确认列表：返回的 Delivery 的 deliveryCount 固定为 0，调用方不得 ack。
     *
     * @param startId 起始条目 id（含），如 {@code "1760400000000-0"}
     * @param endId   结束条目 id（含）
     * @param count   本次最多返回的条数，必须大于 0，否则抛 IllegalArgumentException
     */
    List<Delivery> range(String startId, String endId, int count);
}
