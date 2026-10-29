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
 * @param mailboxLen    该连接进程 Erlang 信箱中待处理的消息数。超过 force_shutdown.max_mailbox_size 即被 EMQX
 *                      强杀、缓冲消息丢失，是「连接被杀」的直接前兆。EMQX 的全局指标
 *                      emqx_vm_process_messages_in_queues 不能代替它：2026-10-29 回看 6 小时，连接信箱冲到
 *                      1.5 万被强杀时那个全局指标也恒为 0
 * @param reductions    该连接进程的累计 reductions（Erlang 的工作量单位）。求速率即该进程的忙碌度：一个进程同一时刻
 *                      只能跑在一个调度器上，单连接时全平台上行都挤过这一个进程，2026-10-29 用 recon 实测 9,000 条/秒
 *                      时它 3 秒 1,500～1,800 万，约为单个设备连接的 350 倍、已顶满单核——有了它这个瓶颈在看板上一眼可见
 */
public record SessionSnapshot(long mqueueDropped, long mqueueLen, long mqueueMax,
                              long inflightCnt, long inflightMax, long sendMsg,
                              long heapWords, boolean durable, long mailboxLen, long reductions) {

    /** 不含信箱与 reductions 的构造（两者记为 0），供只关心其余字段的测试使用。 */
    public SessionSnapshot(long mqueueDropped, long mqueueLen, long mqueueMax,
                           long inflightCnt, long inflightMax, long sendMsg,
                           long heapWords, boolean durable) {
        this(mqueueDropped, mqueueLen, mqueueMax, inflightCnt, inflightMax, sendMsg, heapWords, durable, 0, 0);
    }
}
