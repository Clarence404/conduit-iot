package dev.conduit.server.device;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import dev.conduit.server.tenant.TenantContext;
import dev.conduit.server.tenant.system.DeviceIdentityMapper;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;

/** 设备身份查询：缓存优先，Redis 不可用时降级直查 PostgreSQL。 */
@Component
public class DeviceIdentityLookup {

    private static final Logger log = LoggerFactory.getLogger(DeviceIdentityLookup.class);

    private final DeviceIdentityCache cache;
    private final DeviceIdentityMapper identities;

    public DeviceIdentityLookup(DeviceIdentityCache cache, DeviceIdentityMapper identities) {
        this.cache = cache;
        this.identities = identities;
    }

    /**
     * 按 deviceKey 查设备身份（跨租户）：先查缓存，未命中或 Redis 不可用时直查 PostgreSQL，再回填缓存。
     * 回填失败只记录告警，不影响结果。设备不存在返回 empty（不缓存空结果）。
     */
    public Optional<DeviceIdentity> findForMqtt(String deviceKey) {
        return findWithPurpose(deviceKey, "mqtt-auth");
    }

    /** 流水线按可信的全局 deviceKey 查询设备身份。 */
    public Optional<DeviceIdentity> find(String deviceKey) {
        return findWithPurpose(deviceKey, "pipeline-identity");
    }

    /**
     * 批量查设备身份（流水线按批使用）：先一次 MGET 查缓存，未命中的逐个回源 PostgreSQL 并回填缓存，
     * 与 {@link #find} 使用同样的系统模式与容错：Redis 不可用时（DataAccessException）全部逐个回源，回填失败只记告警。
     * 返回 deviceKey 到身份的映射，查不到的设备不出现在结果中。数据库异常原样抛出（由调用方决定重试或逐条隔离）。
     */
    public Map<String, DeviceIdentity> findAll(Collection<String> deviceKeys) {
        Map<String, DeviceIdentity> result = new HashMap<>();
        if (deviceKeys.isEmpty()) {
            return result;
        }
        boolean cacheUp = true;
        try {
            result.putAll(cache.getAll(deviceKeys));
        } catch (DataAccessException e) {
            cacheUp = false;
            log.warn("Identity cache unavailable, falling back to database: {}", e.getMessage());
        }
        for (String deviceKey : new LinkedHashSet<>(deviceKeys)) {
            if (result.containsKey(deviceKey)) {
                continue;
            }
            DeviceIdentity identity = loadFromDatabase(deviceKey, "pipeline-identity");
            if (identity == null) {
                continue;
            }
            result.put(deviceKey, identity);
            if (cacheUp) {
                cacheUp = fillCache(identity);
            }
        }
        return result;
    }

    /** 缓存未命中时经系统模式访问唯一的跨租户身份 Mapper。 */
    private Optional<DeviceIdentity> findWithPurpose(String deviceKey, String purpose) {
        try {
            Optional<DeviceIdentity> cached = cache.get(deviceKey);
            if (cached.isPresent()) {
                return cached;
            }
        } catch (DataAccessException e) {
            log.warn("Identity cache unavailable, falling back to database: {}", e.getMessage());
        }

        DeviceIdentity identity = loadFromDatabase(deviceKey, purpose);
        if (identity == null) {
            return Optional.empty();
        }
        fillCache(identity);
        return Optional.of(identity);
    }

    /** 经系统模式直查 PostgreSQL，设备不存在返回 null。 */
    private DeviceIdentity loadFromDatabase(String deviceKey, String purpose) {
        return TenantContext.callAsSystem(purpose, () -> identities.findIdentity(deviceKey));
    }

    /** 回填缓存；失败只记告警并返回 false（批量场景据此不再对不可用的 Redis 逐条重试）。 */
    private boolean fillCache(DeviceIdentity identity) {
        try {
            cache.put(identity);
            return true;
        } catch (DataAccessException e) {
            log.warn("Failed to fill identity cache: {}", e.getMessage());
            return false;
        }
    }
}
