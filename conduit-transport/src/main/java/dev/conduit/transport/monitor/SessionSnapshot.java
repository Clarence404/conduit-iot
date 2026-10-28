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
