package dev.conduit.transport.monitor;

/**
 * EMQX 节点内存的一次采样，取自 {@code GET /api/v5/nodes} 的 memory_used / memory_total。
 * 两者是 EMQX 所在主机（容器不设内存上限时即整台主机）的已用与总内存。
 *
 * @param usedBytes  已用字节数
 * @param totalBytes 总字节数，必须大于 0
 */
public record NodeMemory(long usedBytes, long totalBytes) {

    /** 已用占比；总量非正时视为无效数据，返回 NaN。 */
    public double usedRatio() {
        return totalBytes > 0 ? (double) usedBytes / totalBytes : Double.NaN;
    }
}
