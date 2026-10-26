package dev.conduit.server.telemetry;

import dev.conduit.server.thingmodel.DataType;
import dev.conduit.server.thingmodel.EnumItem;

import java.util.List;

/** 历史查询接口 GET /api/v1/devices/{deviceKey}/history 的响应结构（V0.2-B spec 2.3）。 */
public final class HistoryDtos {

    private HistoryDtos() {
    }

    /** 曲线模式：RAW 原始点；AGG 每桶平均/最小/最大（数值测点）；LAST 每桶最后一个值（状态测点）。 */
    public enum Mode {
        RAW, AGG, LAST
    }

    /**
     * 一个测点的曲线。
     *
     * @param name      物模型名称；无物模型时为 null（unit、decimals、enumItems 同理）
     * @param dataType  物模型类型，无物模型时按列类型取 DOUBLE/INT/BOOL；列也不存在时为 null
     * @param enumItems 仅 ENUM 有值
     * @param points    RAW/LAST 为 [ts, value]，AGG 为 [ts, avg, min, max]；聚合无值桶为 null 占位，0/false 保留。
     *                  ts 为毫秒（聚合时为桶起点，可能早于 from）；原始模式不推测采样周期。
     */
    public record SeriesView(String identifier, String name, DataType dataType, String unit, Integer decimals,
                             List<EnumItem> enumItems, Mode mode, List<List<Object>> points) {
    }

    /**
     * 一次查询的结果。所有曲线使用同一模式与时间桶。
     *
     * @param bucketMs 聚合桶宽（毫秒），原始模式为 null
     * @param series   与请求中 properties 的顺序一致
     */
    public record HistoryView(String deviceKey, long from, long to, Long bucketMs, List<SeriesView> series) {
    }
}
