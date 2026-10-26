package dev.conduit.server.telemetry;

import dev.conduit.server.device.DeviceDtos.DeviceView;
import dev.conduit.server.telemetry.HistoryDtos.HistoryView;
import dev.conduit.server.telemetry.HistoryDtos.Mode;
import dev.conduit.server.telemetry.HistoryDtos.SeriesView;
import dev.conduit.server.telemetry.HistoryQueryBuilder.AggColumn;
import dev.conduit.server.telemetry.HistoryQueryBuilder.Scope;
import dev.conduit.server.telemetry.HistoryQueryBuilder.SqlQuery;
import dev.conduit.server.telemetry.InfluxSchema.ColumnType;
import dev.conduit.server.thingmodel.DataType;
import dev.conduit.server.thingmodel.EnumItem;
import dev.conduit.server.thingmodel.PropertySpec;
import dev.conduit.server.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 设备历史查询（V0.2-B spec 2.2）：确定测点类型 → 统计点数 → 选原始或聚合模式 → 执行查询 → 按请求顺序组装曲线。
 * SQL 只使用已校验且经 {@link InfluxSchema} 确认存在的列名；InfluxDB 不可达或返回 5xx 映射为 503，
 * 其他查询错误为 500（只记日志，不向调用方暴露 SQL）。
 */
@Service
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);
    static final String UNAVAILABLE = "时序库暂不可用";
    static final String QUERY_FAILED = "历史查询失败";

    /**
     * 一个请求测点的解析结果。
     *
     * @param spec     物模型定义，无则为 null
     * @param dataType 返回给前端的类型；既无物模型、列也不存在时为 null
     * @param state    是否按状态测点处理（聚合时取桶内最后值）
     * @param exists   InfluxDB 中是否已有该列
     */
    record Resolved(String identifier, PropertySpec spec, DataType dataType, boolean state, boolean exists) {
    }

    private final InfluxClient client;
    private final InfluxSchema schema;
    private final InfluxProperties props;

    public HistoryService(InfluxClient client, InfluxSchema schema, InfluxProperties props) {
        this.client = client;
        this.schema = schema;
        this.props = props;
    }

    /**
     * 查询一台设备的历史曲线。
     *
     * @param tenantId 当前租户（作为 SQL 参数，只查本租户数据）
     * @param device   已按租户校验过的设备
     * @param model    产品物模型（按标识索引；没有物模型时为空 Map）
     * @param request  已校验的测点与时间范围
     * @return 与请求测点顺序一致的曲线，所有曲线同一模式与时间桶
     * @throws ApiException 400 文本测点；503 时序库不可用；500 其他查询错误
     */
    public HistoryView query(long tenantId, DeviceView device, Map<String, PropertySpec> model,
                             HistoryRequest request) {
        long start = System.nanoTime();
        Map<String, ColumnType> columns = influx(() -> schema.lookup(request.properties()));
        List<Resolved> resolved = request.properties().stream()
                .map(id -> resolve(id, model.get(id), columns.get(id)))
                .toList();
        List<Resolved> present = resolved.stream().filter(Resolved::exists).toList();
        Scope scope = new Scope(tenantId, device.productKey(), device.deviceKey(), request.from(), request.to());

        Map<String, List<List<Object>>> points = Map.of();
        Long bucketMs = null;
        if (!present.isEmpty()) {
            if (fitsRaw(scope, present)) {
                points = queryRaw(scope, present);
            } else {
                Duration bucket = BucketPolicy.bucketFor(request.to() - request.from());
                bucketMs = bucket.toMillis();
                points = queryAggregate(scope, present, bucket);
            }
        }
        boolean aggregated = bucketMs != null;
        log.debug("History query: tenant={} deviceKey={} properties={} present={} mode={} bucketMs={} rangeMs={} elapsedMs={}",
                tenantId, device.deviceKey(), request.properties().size(), present.size(),
                present.isEmpty() ? "EMPTY" : aggregated ? "AGGREGATE" : "RAW", bucketMs,
                request.to() - request.from(), (System.nanoTime() - start) / 1_000_000);
        Map<String, List<List<Object>>> byId = points;
        List<SeriesView> series = resolved.stream()
                .map(r -> series(r, aggregated ? (r.state() ? Mode.LAST : Mode.AGG) : Mode.RAW,
                        byId.getOrDefault(r.identifier(), List.of())))
                .toList();
        return new HistoryView(device.deviceKey(), request.from(), request.to(), bucketMs, series);
    }

    /**
     * 判定一个测点的类型。列类型为字符串/其他（含标签列）或物模型为 TEXT → 400；有物模型按物模型；
     * 无物模型按列类型（Float64 → DOUBLE，Int64 → INT，Boolean → BOOL）；两者都没有 → dataType 为 null 的空曲线。
     *
     * @param column 列类型，列不存在时为 null
     */
    static Resolved resolve(String identifier, PropertySpec spec, ColumnType column) {
        if (column == ColumnType.STRING || column == ColumnType.OTHER) {
            throw ApiException.badRequest("文本测点不支持历史曲线: " + identifier);
        }
        if (spec != null) {
            if (spec.dataType() == DataType.TEXT) {
                throw ApiException.badRequest("文本测点不支持历史曲线: " + identifier);
            }
            boolean state = spec.dataType() == DataType.BOOL || spec.dataType() == DataType.ENUM;
            return new Resolved(identifier, spec, spec.dataType(), state, column != null);
        }
        if (column == null) {
            return new Resolved(identifier, null, null, false, false);
        }
        DataType type = switch (column) {
            case FLOAT -> DataType.DOUBLE;
            case INTEGER -> DataType.INT;
            case BOOLEAN -> DataType.BOOL;
            case STRING, OTHER -> throw new IllegalStateException("unreachable: " + column);
        };
        return new Resolved(identifier, null, type, type == DataType.BOOL, true);
    }

    /** 统计每个测点在 [from, to) 内的非空点数；全部不超过 {@link BucketPolicy#MAX_POINTS} 时返回 true。 */
    private boolean fitsRaw(Scope scope, List<Resolved> present) {
        List<String> ids = present.stream().map(Resolved::identifier).toList();
        List<Map<String, Object>> rows = run(HistoryQueryBuilder.count(scope, ids));
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.getFirst();
        for (int i = 0; i < ids.size(); i++) {
            if (row.get(HistoryQueryBuilder.countAlias(i)) instanceof Number n
                    && n.longValue() > BucketPolicy.MAX_POINTS) {
                return false;
            }
        }
        return true;
    }

    /** 原始模式：逐行取各列的非空值，得到按时间升序的 [ts, value]。 */
    private Map<String, List<List<Object>>> queryRaw(Scope scope, List<Resolved> present) {
        List<String> ids = present.stream().map(Resolved::identifier).toList();
        Map<String, List<List<Object>>> result = new HashMap<>();
        for (Map<String, Object> row : run(HistoryQueryBuilder.raw(scope, ids))) {
            long ts = InfluxTime.epochMillis(row.get("time"));
            for (int i = 0; i < ids.size(); i++) {
                Object value = row.get(HistoryQueryBuilder.valueAlias(i));
                if (value != null) {
                    result.computeIfAbsent(ids.get(i), k -> new ArrayList<>()).add(List.of(ts, value));
                }
            }
        }
        return result;
    }

    /** 聚合模式补齐时间桶；无值使用 null 占位，保留真实 0/false，禁止跨缺桶连线。 */
    private Map<String, List<List<Object>>> queryAggregate(Scope scope, List<Resolved> present, Duration bucket) {
        List<AggColumn> columns = present.stream().map(r -> new AggColumn(r.identifier(), r.state())).toList();
        Map<String, List<List<Object>>> result = new HashMap<>();
        for (Map<String, Object> row : run(HistoryQueryBuilder.aggregate(scope, columns, bucket.toSeconds()))) {
            long ts = InfluxTime.epochMillis(row.get("t"));
            for (int i = 0; i < columns.size(); i++) {
                AggColumn column = columns.get(i);
                List<Object> point = null;
                if (column.state()) {
                    Object last = row.get(HistoryQueryBuilder.lastAlias(i));
                    if (last != null) {
                        point = List.of(ts, last);
                    }
                } else {
                    Object avg = row.get(HistoryQueryBuilder.avgAlias(i));
                    if (avg != null) {
                        point = List.of(ts, avg, row.get(HistoryQueryBuilder.minAlias(i)),
                                row.get(HistoryQueryBuilder.maxAlias(i)));
                    }
                }
                if (point != null) {
                    result.computeIfAbsent(column.identifier(), k -> new ArrayList<>()).add(point);
                }
            }
        }
        long width = bucket.toMillis();
        long first = Math.floorDiv(scope.from(), width) * width;
        for (AggColumn column : columns) {
            Map<Long, List<Object>> byTime = new HashMap<>();
            result.getOrDefault(column.identifier(), List.of()).forEach(p -> byTime.put((Long) p.getFirst(), p));
            List<List<Object>> aligned = new ArrayList<>();
            for (long ts = first; ts < scope.to(); ts += width) {
                List<Object> point = byTime.get(ts);
                aligned.add(point != null ? point : column.state()
                        ? Arrays.asList(ts, null) : Arrays.asList(ts, null, null, null));
            }
            result.put(column.identifier(), aligned);
        }
        return result;
    }

    /** 组装一条曲线的元数据与点。 */
    private static SeriesView series(Resolved r, Mode mode, List<List<Object>> points) {
        PropertySpec spec = r.spec();
        return new SeriesView(r.identifier(), spec == null ? null : spec.name(), r.dataType(),
                spec == null ? null : spec.unit(), spec == null ? null : spec.decimals(), enumItems(spec), mode,
                points);
    }

    /** 枚举项（保持物模型中的顺序）；非枚举为 null。 */
    private static List<EnumItem> enumItems(PropertySpec spec) {
        if (spec == null || spec.dataType() != DataType.ENUM) {
            return null;
        }
        return spec.enumLabels().entrySet().stream().map(e -> new EnumItem(e.getKey(), e.getValue())).toList();
    }

    /** 执行一条查询；DEBUG 记录 SQL 文本、参数（租户/设备 Key、时间范围，无密钥）、返回行数与耗时。 */
    private List<Map<String, Object>> run(SqlQuery query) {
        long start = System.nanoTime();
        List<Map<String, Object>> rows = influx(() -> client.querySql(props.database(), query.sql(), query.params()));
        if (log.isDebugEnabled()) {
            log.debug("History SQL ({} rows, {} ms): {} params={}", rows.size(), (System.nanoTime() - start) / 1_000_000,
                    query.sql(), query.params());
        }
        return rows;
    }

    /**
     * 执行一次 InfluxDB 调用并映射异常：不可达或 5xx → 503「时序库暂不可用」；其他 HTTP 错误 → 500「历史查询失败」。
     * 原始错误（可能含 SQL）只写日志。
     */
    private <T> T influx(Supplier<T> call) {
        try {
            return call.get();
        } catch (UncheckedIOException e) {
            log.warn("InfluxDB unreachable during history query: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
        } catch (InfluxHttpException e) {
            if (e.status() >= 500) {
                log.warn("InfluxDB error during history query: {}", e.getMessage());
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
            }
            log.error("History query rejected by InfluxDB", e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, QUERY_FAILED);
        }
    }
}
