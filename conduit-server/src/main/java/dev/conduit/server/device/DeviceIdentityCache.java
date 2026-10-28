package dev.conduit.server.device;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 设备身份的 Redis 缓存（conduit:auth:{deviceKey}，TTL 5 分钟）。MQTT 鉴权与流水线按 deviceKey 高频查询身份，用它减轻 PostgreSQL 压力。
 * 设备状态变更、重置密钥、删除时由 DeviceService 主动 evict；读取失败时降级由 DeviceIdentityLookup 处理。
 */
@Component
public class DeviceIdentityCache {

    private static final Logger log = LoggerFactory.getLogger(DeviceIdentityCache.class);

    static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public DeviceIdentityCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 身份缓存的 Redis 键。
     */
    public static String key(String deviceKey) {
        return "conduit:auth:" + deviceKey;
    }

    /**
     * 读缓存；未命中返回 empty。缓存内容损坏时记录告警、删除该条并按未命中处理。Redis 异常（DataAccessException）原样抛出。
     */
    public Optional<DeviceIdentity> get(String deviceKey) {
        String json = redis.opsForValue().get(key(deviceKey));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, DeviceIdentity.class));
        } catch (JsonProcessingException e) {
            log.warn("Corrupt identity cache entry for {}, evicting: {}", deviceKey, e.getOriginalMessage());
            evict(deviceKey);
            return Optional.empty();
        }
    }

    /**
     * 批量读取设备身份缓存，一次 MGET（流水线按批查身份，避免每条消息一次往返）。入参去重；入参为空不访问 Redis。
     * 只返回命中的部分，未命中的 deviceKey 不出现在结果中；内容损坏的条目与 {@link #get} 一样记录告警、删除并按未命中处理。
     * Redis 异常（DataAccessException）原样抛出。
     */
    public Map<String, DeviceIdentity> getAll(Collection<String> deviceKeys) {
        if (deviceKeys.isEmpty()) {
            return Map.of();
        }
        List<String> unique = List.copyOf(new LinkedHashSet<>(deviceKeys));
        List<String> values = redis.opsForValue().multiGet(unique.stream().map(DeviceIdentityCache::key).toList());
        Map<String, DeviceIdentity> result = new HashMap<>();
        if (values == null) {
            return result;
        }
        for (int i = 0; i < unique.size(); i++) {
            String json = values.get(i);
            if (json == null) {
                continue;
            }
            try {
                result.put(unique.get(i), objectMapper.readValue(json, DeviceIdentity.class));
            } catch (JsonProcessingException e) {
                log.warn("Corrupt identity cache entry for {}, evicting: {}", unique.get(i), e.getOriginalMessage());
                evict(unique.get(i));
            }
        }
        return result;
    }

    /**
     * 写入缓存并设置 5 分钟 TTL。
     */
    public void put(DeviceIdentity identity) {
        try {
            redis.opsForValue().set(key(identity.deviceKey()), objectMapper.writeValueAsString(identity), TTL);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 删除缓存项，使下次查询重新读库。
     */
    public void evict(String deviceKey) {
        redis.delete(key(deviceKey));
    }

    /**
     * 批量删除缓存项（租户停用/启用时清除该租户全部设备的身份，使鉴权立即按新状态判断）；空集合不访问 Redis。
     * Redis 异常（DataAccessException）原样抛出，由调用方决定是否忽略。
     */
    public void evictAll(Collection<String> deviceKeys) {
        if (deviceKeys.isEmpty()) {
            return;
        }
        redis.delete(deviceKeys.stream().map(DeviceIdentityCache::key).toList());
    }
}
