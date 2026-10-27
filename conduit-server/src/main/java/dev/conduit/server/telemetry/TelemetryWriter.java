package dev.conduit.server.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 遥测写入器：把流水线产出的 TelemetryPoint 批量写入 InfluxDB 的 property 表（tag：tenant_id/product_key/device_key）。
 * 位于流水线「物模型校验」之后、最新值更新之前。
 */
@Component
public class TelemetryWriter {

    static final String MEASUREMENT = "property";
    private static final Logger log = LoggerFactory.getLogger(TelemetryWriter.class);

    private final InfluxClient client;
    private final InfluxProperties props;

    public TelemetryWriter(InfluxClient client, InfluxProperties props) {
        this.client = client;
        this.props = props;
    }

    /**
     * 一次请求写入整批。无法转换的点（不支持的类型、null 等）在写入前拒绝；InfluxDB 逐行拒绝的点（通常是字段类型冲突）
     * 按 line_number 映射回原始下标；其余行已写入。下游不可用抛 TelemetryUnavailableException（可原地重试）；
     * 非逐行拒绝的 400 说明请求本身有问题，抛 IllegalStateException（不可原地重试，见实施计划 Task 1 的决定）。
     */
    public WriteResult write(List<TelemetryPoint> points) {
        if (points.isEmpty()) {
            return WriteResult.ok();
        }
        List<WriteResult.Rejected> rejected = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            try {
                lines.add(toLine(points.get(i)));
                indexes.add(i);
            } catch (RuntimeException e) {
                rejected.add(new WriteResult.Rejected(i, String.valueOf(e.getMessage())));
            }
        }
        if (lines.isEmpty()) {
            return new WriteResult(rejected);
        }
        // 按字节切块：批大小可以不限，但单次请求必须小于 InfluxDB 的 max_http_request_size，
        // 否则返回 413，而重发同样的载荷永远不会成功（实测会让流水线无限重试、彻底停摆）。
        for (int from = 0; from < lines.size(); ) {
            int to = chunkEnd(lines, from);
            for (InfluxClient.LineError error : send(lines.subList(from, to))) {
                int line = from + error.lineNumber() - 1;
                if (line < from || line >= to) {
                    throw new IllegalStateException("InfluxDB reported rejected line " + error.lineNumber()
                            + " outside the chunk [" + from + ", " + to + ") of " + lines.size());
                }
                rejected.add(new WriteResult.Rejected(indexes.get(line), error.message()));
            }
            from = to;
        }
        rejected.sort(Comparator.comparingInt(WriteResult.Rejected::index));
        return new WriteResult(rejected);
    }

    /**
     * 从 from 起累加行长度（含换行），返回不超过 {@code conduit.influx.max-request-bytes} 的结束下标（不含）。
     * 单行本身就超限时仍单独成块——让 InfluxDB 明确拒绝它，好过无声丢弃。
     */
    private int chunkEnd(List<String> lines, int from) {
        long bytes = 0;
        for (int i = from; i < lines.size(); i++) {
            long size = lines.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L;
            if (i > from && bytes + size > props.maxRequestBytes()) {
                return i;
            }
            bytes += size;
        }
        return lines.size();
    }

    /**
     * 发送并翻译异常：400（非逐行拒绝）与 413（请求体过大）转 IllegalStateException——两者重发同样的载荷都不会成功，
     * 不能当作可原地重试的「下游不可用」；其余 HTTP 错误和 IO 错误统一转 TelemetryUnavailableException。
     */
    private List<InfluxClient.LineError> send(List<String> lines) {
        if (log.isTraceEnabled()) {
            // 行协议含测点值，按每行 256 字符截断
            lines.forEach(l -> log.trace("Influx line: {}", l.length() <= 256 ? l : l.substring(0, 256) + "...(truncated)"));
        }
        try {
            return client.writeLines(props.database(), lines);
        } catch (InfluxHttpException e) {
            if (e.status() == 400 || e.status() == 413) {
                throw new IllegalStateException("InfluxDB rejected the write request itself: " + e.getMessage(), e);
            }
            throw new TelemetryUnavailableException(e);
        } catch (RuntimeException e) {
            throw new TelemetryUnavailableException(e);
        }
    }

    /**
     * 把遥测点转成行协议；tag 为 tenant_id、product_key、device_key。
     */
    static String toLine(TelemetryPoint p) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("tenant_id", Long.toString(p.tenantId()));
        tags.put("product_key", p.productKey());
        tags.put("device_key", p.deviceKey());
        return LineProtocol.line(MEASUREMENT, tags, p.fields(), p.ts());
    }
}
