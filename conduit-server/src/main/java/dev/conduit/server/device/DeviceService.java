package dev.conduit.server.device;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.conduit.server.device.DeviceDtos.CreateDeviceRequest;
import dev.conduit.server.device.DeviceDtos.AssignDeviceRequest;
import dev.conduit.server.device.DeviceDtos.DeviceAssignmentView;
import dev.conduit.server.device.DeviceDtos.DeviceCreated;
import dev.conduit.server.device.DeviceDtos.DeviceView;
import dev.conduit.server.device.DeviceDtos.SecretView;
import dev.conduit.server.device.DeviceDtos.UpdateDeviceRequest;
import dev.conduit.server.presence.Presence;
import dev.conduit.server.organization.Asset;
import dev.conduit.server.organization.OrganizationService;
import dev.conduit.server.organization.Site;
import dev.conduit.server.organization.SiteMapper;
import dev.conduit.server.presence.PresenceStore;
import dev.conduit.server.product.Product;
import dev.conduit.server.product.ProductMapper;
import dev.conduit.server.product.ProductService;
import dev.conduit.server.security.KeyGenerator;
import dev.conduit.server.security.SecretHasher;
import dev.conduit.server.telemetry.LatestValueStore;
import dev.conduit.server.web.ApiException;
import dev.conduit.server.web.PageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 设备管理：创建、查询、修改、重置密钥、删除。设备属于当前租户（租户过滤由拦截器完成）；
 * 在线状态来自 Redis Presence，最近上报时间来自最新值存储。涉及接入鉴权的变更要同步清除身份缓存。
 */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceMapper devices;
    private final ProductMapper products;
    private final ProductService productService;
    private final KeyGenerator keys;
    private final SecretHasher hasher;
    private final DeviceIdentityCache identityCache;
    private final LatestValueStore latest;
    private final PresenceStore presence;
    private final Clock clock;
    private final SiteMapper sites;
    private final OrganizationService organizations;

    public DeviceService(DeviceMapper devices, ProductMapper products, ProductService productService,
                         KeyGenerator keys, SecretHasher hasher, DeviceIdentityCache identityCache,
                         LatestValueStore latest, PresenceStore presence, Clock clock,
                         SiteMapper sites, OrganizationService organizations) {
        this.devices = devices;
        this.products = products;
        this.productService = productService;
        this.keys = keys;
        this.hasher = hasher;
        this.identityCache = identityCache;
        this.latest = latest;
        this.presence = presence;
        this.clock = clock;
        this.sites = sites;
        this.organizations = organizations;
    }

    /**
     * 创建设备：校验产品存在（不存在抛 404），生成 deviceKey 与明文密钥，库中只存加盐哈希。明文密钥只在返回值中出现这一次。
     */
    public DeviceCreated create(CreateDeviceRequest request) {
        Product product = productService.require(request.productKey());
        String secret = keys.deviceSecret();
        String salt = hasher.newSalt();

        Device d = new Device();
        d.setProductId(product.getId());
        Site site = request.siteCode() == null || request.siteCode().isBlank()
                ? defaultSite() : organizations.requireSite(request.siteCode());
        Asset asset = organizations.optionalAsset(request.assetCode());
        if (asset != null && !asset.getSiteId().equals(site.getId())) {
            throw ApiException.badRequest("资产不属于所选站点");
        }
        d.setSiteId(site.getId());
        d.setAssetId(asset == null ? null : asset.getId());
        d.setDeviceKey(keys.deviceKey());
        d.setName(request.name());
        d.setSecretSalt(salt);
        d.setSecretHash(hasher.hash(secret, salt));
        d.setStatus(Device.ENABLED);
        devices.insert(d);

        Device saved = devices.selectById(d.getId());
        return new DeviceCreated(toViews(List.of(saved)).getFirst(), secret);
    }

    /** 「最近上报」的时间窗口：一小时。 */
    private static final long RECENT_WINDOW_MILLIS = 3_600_000L;

    /**
     * 分页列出设备，按 id 倒序，支持 {@link DeviceFilter} 的全部筛选。page 从 1 开始。
     * <p>
     * 产品、关键字、状态、创建时间在 SQL 中过滤（仍受租户拦截器限定）；productKey 指向不存在的产品抛 404。
     * 没有 online / reported 时走数据库分页。带 online 或 reported 时，它们依赖 Redis，只能：先从 SQL 取出全部匹配设备
     * （仅 id 与 deviceKey，不分页），批量读取在线状态与最近上报时间，内存中过滤再分页，因此 total 是过滤后的数量，
     * 视图只为当前页构建。Redis 不可用时抛 503「在线状态暂不可用」。
     * <p>
     * 注意：内存路径的开销是 O(设备数)（每次请求读取全部匹配设备的 Redis 状态）。设备规模到万级时，
     * 应改为维护在线集合（如 Redis Set / ZSet）后直接分页，不再逐设备读取。
     */
    public PageView<DeviceView> list(DeviceFilter filter, int page, int size) {
        LambdaQueryWrapper<Device> query = new LambdaQueryWrapper<Device>().orderByDesc(Device::getId);
        if (filter.productKey() != null) {
            query.eq(Device::getProductId, productService.require(filter.productKey()).getId());
        }
        if (filter.keyword() != null) {
            String keyword = filter.keyword();
            query.and(q -> q.like(Device::getName, keyword).or().like(Device::getDeviceKey, keyword));
        }
        if (filter.status() != null) {
            query.eq(Device::getStatus, filter.status());
        }
        if (filter.createdFrom() != null) {
            query.ge(Device::getCreatedAt, utc(filter.createdFrom()));
        }
        if (filter.createdTo() != null) {
            query.lt(Device::getCreatedAt, utc(filter.createdTo()));
        }
        if (!filter.needsRedis()) {
            Page<Device> result = devices.selectPage(new Page<>(page, size), query);
            return new PageView<>(toViews(result.getRecords()), result.getTotal(), page, size);
        }
        return listWithRedisFilters(filter, query, page, size);
    }

    private static OffsetDateTime utc(long epochMillis) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
    }

    /**
     * online / reported 筛选的内存路径：SQL 取全部匹配的 id 与 deviceKey，批量读 Redis，过滤后分页，只为当前页构建视图。
     * Redis 不可用时抛 503。
     */
    private PageView<DeviceView> listWithRedisFilters(DeviceFilter filter, LambdaQueryWrapper<Device> query,
                                                      int page, int size) {
        query.select(Device::getId, Device::getDeviceKey);
        List<Device> rows = devices.selectList(query);
        List<String> deviceKeys = rows.stream().map(Device::getDeviceKey).toList();

        // 在线状态与最近上报都读：筛选用其一，页面视图直接复用这两份结果，不再二次读取 Redis
        Map<String, Presence> presenceByDevice;
        Map<String, Long> lastReports;
        try {
            presenceByDevice = presence.getAll(deviceKeys);
            lastReports = latest.lastReportTimes(deviceKeys);
        } catch (DataAccessException e) {
            log.warn("Redis unavailable for device list filters", e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "在线状态暂不可用");
        }

        long recentSince = clock.millis() - RECENT_WINDOW_MILLIS;
        List<Device> matched = rows.stream().filter(d -> {
            if (filter.online() != null) {
                Presence p = presenceByDevice.get(d.getDeviceKey());
                if ((p != null && p.online()) != filter.online()) {
                    return false;
                }
            }
            if (filter.reported() != null) {
                Long last = lastReports.get(d.getDeviceKey());
                return switch (filter.reported()) {
                    case NEVER -> last == null;
                    case RECENT -> last != null && last >= recentSince;
                    case STALE -> last != null && last < recentSince;
                };
            }
            return true;
        }).toList();

        int from = (int) Math.min((long) (page - 1) * size, matched.size());
        int to = (int) Math.min((long) from + size, matched.size());
        List<Long> pageIds = matched.subList(from, to).stream().map(Device::getId).toList();
        Map<Long, Device> full = pageIds.isEmpty() ? Map.of()
                : devices.selectBatchIds(pageIds).stream().collect(Collectors.toMap(Device::getId, d -> d));
        List<Device> pageRows = pageIds.stream().map(full::get).filter(Objects::nonNull).toList();
        return new PageView<>(toViews(pageRows, presenceByDevice, lastReports), matched.size(), page, size);
    }

    /**
     * 查询单个设备（含在线状态与最近上报时间），不存在抛 404。
     */
    public DeviceView get(String deviceKey) {
        return toViews(List.of(require(deviceKey))).getFirst();
    }

    /**
     * 修改名称与启用状态。只更新这两列（以及更新时间），不会覆盖并发重置的密钥；状态确有变化时清除身份缓存，使禁用/启用立即对鉴权生效。
     */
    public DeviceView update(String deviceKey, UpdateDeviceRequest request) {
        Device d = require(deviceKey);
        boolean statusChanged = !d.getStatus().equals(request.status());
        // 只更新 name/status，避免用旧快照覆盖并发 reset-secret 写入的密钥
        devices.update(null, new LambdaUpdateWrapper<Device>()
                .eq(Device::getId, d.getId())
                .set(Device::getName, request.name())
                .set(Device::getStatus, request.status())
                .set(Device::getUpdatedAt, OffsetDateTime.now(clock)));
        if (statusChanged) {
            identityCache.evict(deviceKey);
        }
        return toViews(List.of(require(deviceKey))).getFirst();
    }

    /** 查询设备的组织归属。 */
    public DeviceAssignmentView assignment(String deviceKey) {
        Device d = require(deviceKey);
        Site site = sites.selectById(d.getSiteId());
        Asset asset = d.getAssetId() == null ? null : organizations.optionalAssetById(d.getAssetId());
        return new DeviceAssignmentView(deviceKey, site.getCode(), asset == null ? null : asset.getCode());
    }

    /** 把设备移动到站点或站点内资产；产品归属不变。 */
    public DeviceAssignmentView assign(String deviceKey, AssignDeviceRequest request) {
        Device d = require(deviceKey);
        Site site = organizations.requireSite(request.siteCode());
        Asset asset = organizations.optionalAsset(request.assetCode());
        if (asset != null && !asset.getSiteId().equals(site.getId())) {
            throw ApiException.badRequest("资产不属于所选站点");
        }
        devices.update(null, new LambdaUpdateWrapper<Device>()
                .eq(Device::getId, d.getId())
                .set(Device::getSiteId, site.getId())
                .set(Device::getAssetId, asset == null ? null : asset.getId())
                .set(Device::getUpdatedAt, OffsetDateTime.now(clock)));
        return new DeviceAssignmentView(deviceKey, site.getCode(), asset == null ? null : asset.getCode());
    }

    /**
     * 重置设备密钥：生成新密钥与盐，更新后清除身份缓存，旧密钥随之失效。返回新的明文密钥（仅此一次）。
     */
    public SecretView resetSecret(String deviceKey) {
        Device d = require(deviceKey);
        String secret = keys.deviceSecret();
        String salt = hasher.newSalt();
        devices.update(null, new LambdaUpdateWrapper<Device>()
                .eq(Device::getId, d.getId())
                .set(Device::getSecretHash, hasher.hash(secret, salt))
                .set(Device::getSecretSalt, salt)
                .set(Device::getUpdatedAt, OffsetDateTime.now(clock)));
        identityCache.evict(deviceKey);
        return new SecretView(deviceKey, secret);
    }

    /**
     * 删除设备。先删库，再尽力清理 Redis（身份缓存、最新值、在线状态）；Redis 清理失败只记录告警，不影响删除结果。InfluxDB 历史数据不删，随保留期过期。
     */
    public void delete(String deviceKey) {
        Device d = require(deviceKey);
        devices.deleteById(d.getId());
        bestEffort(deviceKey, "auth", () -> identityCache.evict(deviceKey));
        bestEffort(deviceKey, "latest", () -> latest.delete(deviceKey));
        bestEffort(deviceKey, "presence", () -> presence.delete(deviceKey));
    }

    /**
     * 执行清理动作，Redis 异常只记录日志，不向上抛出。
     */
    private void bestEffort(String deviceKey, String keyName, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (DataAccessException e) {
            log.warn("Redis cleanup failed after device delete: deviceKey={}, key={}", deviceKey, keyName, e);
        }
    }

    /**
     * 按 deviceKey 取当前租户的设备实体，不存在（含属于其他租户）抛 404。供其他服务复用。
     */
    public Device require(String deviceKey) {
        Device d = devices.selectOne(new LambdaQueryWrapper<Device>().eq(Device::getDeviceKey, deviceKey));
        if (d == null) {
            throw ApiException.notFound("设备不存在: " + deviceKey);
        }
        return d;
    }

    /**
     * 批量转为响应视图：产品标识、在线状态、最近上报时间都各用一次批量查询取得，避免逐个设备查询。
     */
    private List<DeviceView> toViews(List<Device> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<String> deviceKeys = list.stream().map(Device::getDeviceKey).toList();
        return toViews(list, presence.getAll(deviceKeys), latest.lastReportTimes(deviceKeys));
    }

    /**
     * 用已经读好的在线状态与最近上报时间构建视图（筛选路径复用，避免再读一次 Redis）；产品标识仍批量查一次库。
     */
    private List<DeviceView> toViews(List<Device> list, Map<String, Presence> presenceByDevice,
                                     Map<String, Long> lastReports) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, String> productKeys = products.selectBatchIds(list.stream().map(Device::getProductId).distinct().toList())
                .stream().collect(Collectors.toMap(Product::getId, Product::getProductKey));
        var authorization=dev.conduit.server.authorization.AuthorizationContext.current();
        Map<Long,Site> siteById=authorization==null ? Map.of() : sites.selectBatchIds(list.stream().map(Device::getSiteId).distinct().toList())
                .stream().collect(Collectors.toMap(Site::getId,s->s));
        return list.stream().map(d -> {
            Presence p = presenceByDevice.get(d.getDeviceKey());
            java.util.Set<String> permissions=null;
            if(authorization!=null) {
                Site site=siteById.get(d.getSiteId());
                permissions=dev.conduit.server.authorization.AuthorizationContext.permissions(site==null ? null : site.getProjectId(),d.getSiteId());
            }
            return new DeviceView(d.getDeviceKey(), d.getName(), productKeys.get(d.getProductId()), d.getStatus(),
                    p != null && p.online(), lastReports.get(d.getDeviceKey()), d.getCreatedAt(),permissions);
        }).toList();
    }

    private Site defaultSite() {
        Site site = sites.selectOne(new LambdaQueryWrapper<Site>().eq(Site::getIsDefault, true));
        if (site == null) {
            throw ApiException.notFound("默认站点不存在或无权限，请指定已授权站点");
        }
        return site;
    }
}
