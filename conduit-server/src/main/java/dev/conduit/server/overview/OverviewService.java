package dev.conduit.server.overview;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.conduit.server.device.Device;
import dev.conduit.server.device.DeviceStats;
import dev.conduit.server.device.DeviceStats.ProductCounts;
import dev.conduit.server.overview.OverviewDtos.DeadSummary;
import dev.conduit.server.overview.OverviewDtos.OverviewView;
import dev.conduit.server.overview.OverviewDtos.ProductStat;
import dev.conduit.server.overview.OverviewDtos.RecentDevice;
import dev.conduit.server.product.Product;
import dev.conduit.server.product.ProductMapper;
import dev.conduit.server.telemetry.LatestValueStore;
import dev.conduit.server.thingmodel.ProductPropertyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 首页总览：按当前租户统计产品、属性、设备（启用/禁用/在线）、最近上报的设备、各产品设备数与最近 1 小时死信。
 * 产品、属性与设备计数来自 PostgreSQL（租户拦截器过滤）；在线数、最后上报时间与死信来自 Redis，
 * 这三项各自容错：Redis 不可用时对应字段为 null（最近上报列表为空），其余照常返回。
 * 本期把租户设备全部读出再统计，设备到万级时改为增量计数（见 docs/backlog-v0.2.md）。
 */
@Service
public class OverviewService {

    static final Duration DEAD_WINDOW = Duration.ofHours(1);
    static final int DEAD_SCAN_LIMIT = 10_000;
    static final int RECENT_LIMIT = 5;
    private static final String UNKNOWN_REASON = "UNKNOWN";
    private static final Logger log = LoggerFactory.getLogger(OverviewService.class);

    private final ProductMapper products;
    private final ProductPropertyMapper properties;
    private final DeviceStats stats;
    private final LatestValueStore latest;
    private final DeadLetterReader deadLetters;
    private final Clock clock;

    public OverviewService(ProductMapper products, ProductPropertyMapper properties, DeviceStats stats,
                           LatestValueStore latest, DeadLetterReader deadLetters, Clock clock) {
        this.products = products;
        this.properties = properties;
        this.stats = stats;
        this.latest = latest;
        this.deadLetters = deadLetters;
        this.clock = clock;
    }

    /**
     * 组装当前租户的总览（只读）。
     *
     * @return 总览；Redis 不可用时 onlineCount、products[].onlineCount、deadLastHour 为 null，recentDevices 为空
     */
    public OverviewView overview() {
        long start = System.nanoTime();
        List<Product> productList = products.selectList(new LambdaQueryWrapper<Product>().orderByDesc(Product::getId));
        int propertyCount = Math.toIntExact(properties.selectCount(null));
        DeviceStats.Snapshot snapshot = stats.snapshot(null);
        Map<Long, Product> productsById = productList.stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        int deviceCount = snapshot.deviceCount();
        int enabledCount = snapshot.enabledCount();
        var authorization = dev.conduit.server.authorization.AuthorizationContext.current();
        DeadSummary dead = authorization != null && !authorization.scope().tenantPermission("device.read")
                ? null : deadLastHour(productList);
        List<RecentDevice> recent = recentDevices(snapshot.devices(), productsById);
        OverviewView view = new OverviewView(productList.size(), propertyCount, deviceCount, enabledCount,
                deviceCount - enabledCount, snapshot.onlineCount(), dead, recent,
                productStats(productList, snapshot));
        log.debug("Overview: products={} properties={} devices={} online={} deadLastHour={} recent={} elapsedMs={}",
                productList.size(), propertyCount, deviceCount, snapshot.onlineCount(),
                dead == null ? null : dead.total(), recent.size(), (System.nanoTime() - start) / 1_000_000);
        return view;
    }

    /**
     * 最近 1 小时的死信：只统计 pk 属于当前租户产品的条目，按原因分组。死信流读不到（Redis 不可用）时返回 null。
     */
    private DeadSummary deadLastHour(List<Product> productList) {
        Set<String> productKeys = productList.stream().map(Product::getProductKey).collect(Collectors.toSet());
        List<Map<String, String>> records;
        try {
            records = deadLetters.since(clock.millis() - DEAD_WINDOW.toMillis(), DEAD_SCAN_LIMIT);
        } catch (DataAccessException e) {
            log.warn("Dead-letter stream unavailable, deadLastHour omitted", e);
            return null;
        }
        return summarize(records, productKeys);
    }

    /**
     * 统计属于给定产品的死信条数并按原因分组：没有 pk 或 pk 不在 productKeys 中的条目跳过；原因缺失记为 UNKNOWN；
     * 结果的原因按名称排序。
     */
    static DeadSummary summarize(List<Map<String, String>> records, Set<String> productKeys) {
        Map<String, Integer> byReason = new TreeMap<>();
        int total = 0;
        for (Map<String, String> record : records) {
            String productKey = record.get("pk");
            if (productKey == null || !productKeys.contains(productKey)) {
                continue;
            }
            byReason.merge(record.getOrDefault("reason", UNKNOWN_REASON), 1, Integer::sum);
            total++;
        }
        return new DeadSummary(total, byReason);
    }

    /**
     * 最后上报时间最近的设备（最多 RECENT_LIMIT 台，新的在前；从未上报的不列出）。
     * 最后上报时间读不到（Redis 不可用）时返回空列表。
     */
    private List<RecentDevice> recentDevices(List<Device> devices, Map<Long, Product> productsById) {
        if (devices.isEmpty()) {
            return List.of();
        }
        Map<String, Long> times;
        try {
            times = latest.lastReportTimes(devices.stream().map(Device::getDeviceKey).toList());
        } catch (DataAccessException e) {
            log.warn("Latest-value store unavailable, recentDevices omitted", e);
            return List.of();
        }
        return devices.stream()
                .filter(d -> times.containsKey(d.getDeviceKey()))
                .sorted(Comparator.comparing((Device d) -> times.get(d.getDeviceKey())).reversed())
                .limit(RECENT_LIMIT)
                .map(d -> {
                    Product p = productsById.get(d.getProductId());
                    return new RecentDevice(d.getDeviceKey(), d.getName(), p == null ? null : p.getProductKey(),
                            p == null ? null : p.getName(), times.get(d.getDeviceKey()));
                })
                .toList();
    }

    /**
     * 每个产品的设备数与在线数，按设备数降序；设备数相同的保持产品 id 倒序（新的在前，排序是稳定的）。
     */
    private static List<ProductStat> productStats(List<Product> productList, DeviceStats.Snapshot snapshot) {
        return productList.stream()
                .map(p -> {
                    ProductCounts c = snapshot.of(p.getId());
                    return new ProductStat(p.getProductKey(), p.getName(), c.deviceCount(), c.onlineCount());
                })
                .sorted(Comparator.comparingInt(ProductStat::deviceCount).reversed())
                .toList();
    }
}
