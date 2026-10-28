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

    /** 强制 id / failure 二选一：两者都有或都没有都无法表达「这条到底成没成」，调用方会误确认或误重投。 */
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
