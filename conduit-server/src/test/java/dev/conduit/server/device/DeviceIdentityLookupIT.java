package dev.conduit.server.device;

import dev.conduit.server.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class DeviceIdentityLookupIT extends IntegrationTestBase {

    @Autowired
    DeviceIdentityLookup lookup;

    @Autowired
    DeviceIdentityCache cache;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    dev.conduit.server.tenant.system.DeviceIdentityMapper identityMapper;

    @Test
    void loadsFromDatabaseAndFillsCache() {
        TestDevice device = createDevice(defaultTenantId(), "ENABLED");

        assertThat(cache.get(device.deviceKey())).isEmpty();
        DeviceIdentity identity = lookup.find(device.deviceKey()).orElseThrow();

        assertThat(identity.productKey()).isEqualTo(device.productKey());
        assertThat(identity.tenantId()).isEqualTo(device.tenantId());
        assertThat(cache.get(device.deviceKey())).contains(identity);
        assertThat(redis.getExpire(DeviceIdentityCache.key(device.deviceKey()))).isBetween(1L, 300L);
    }

    @Test
    void servesFromCacheWithoutDatabase() {
        DeviceIdentity cached = new DeviceIdentity(unique("dk"), 99, "ENABLED", "h", "s", "pk", "ACTIVE");
        cache.put(cached);

        assertThat(lookup.find(cached.deviceKey())).contains(cached);
    }

    @Test
    void unknownDeviceIsEmptyAndNotCached() {
        String dk = unique("dk");

        assertThat(lookup.find(dk)).isEmpty();
        assertThat(cache.get(dk)).isEmpty();
    }

    @Test
    void evictRemovesCacheEntry() {
        DeviceIdentity cached = new DeviceIdentity(unique("dk"), 1, "ENABLED", "h", "s", "pk", "ACTIVE");
        cache.put(cached);

        cache.evict(cached.deviceKey());

        assertThat(cache.get(cached.deviceKey())).isEmpty();
    }

    @Test
    void corruptCacheEntryFallsBackToDatabaseAndIsRepaired() {
        TestDevice device = createDevice(defaultTenantId(), "ENABLED");
        redis.opsForValue().set(DeviceIdentityCache.key(device.deviceKey()), "{not json");

        DeviceIdentity identity = lookup.find(device.deviceKey()).orElseThrow();

        assertThat(identity.productKey()).isEqualTo(device.productKey());
        assertThat(cache.get(device.deviceKey())).contains(identity);
    }

    @Test
    void legacyEntryWithoutTenantStatusIsServedAsActiveWithoutRepair(CapturedOutput output) throws Exception {
        String dk = unique("dk");
        // V0.2-C 之前写入的缓存条目：没有 tenantStatus 字段
        String legacy = ("{\"deviceKey\":\"%s\",\"tenantId\":7,\"status\":\"ENABLED\",\"secretHash\":\"h\","
                + "\"secretSalt\":\"s\",\"productKey\":\"pk\"}").formatted(dk);
        redis.opsForValue().set(DeviceIdentityCache.key(dk), legacy);
        // 注入的 Spring ObjectMapper 能直接读出旧条目
        assertThat(objectMapper.readValue(legacy, DeviceIdentity.class).tenantStatus()).isNull();

        DeviceIdentity identity = lookup.find(dk).orElseThrow();

        assertThat(identity.tenantStatus()).isNull();
        assertThat(identity.tenantActive()).isTrue();
        assertThat(identity.tenantId()).isEqualTo(7);
        // 没有走损坏条目的修复路径：不记告警、缓存里仍是原样的旧条目
        assertThat(output).doesNotContain("Corrupt identity cache entry");
        assertThat(redis.opsForValue().get(DeviceIdentityCache.key(dk))).isEqualTo(legacy);
    }

    @Test
    void cacheGetAllHandlesFullPartialNoneAndEmpty() {
        DeviceIdentity a = new DeviceIdentity(unique("dk"), 1, "ENABLED", "h", "s", "pk", "ACTIVE");
        DeviceIdentity b = new DeviceIdentity(unique("dk"), 1, "ENABLED", "h", "s", "pk", "ACTIVE");
        String missing = unique("dk");
        cache.put(a);
        cache.put(b);

        assertThat(cache.getAll(List.of(a.deviceKey(), b.deviceKey())))
                .containsOnlyKeys(a.deviceKey(), b.deviceKey()).containsEntry(a.deviceKey(), a);
        assertThat(cache.getAll(List.of(a.deviceKey(), missing, a.deviceKey())))
                .containsOnlyKeys(a.deviceKey());
        assertThat(cache.getAll(List.of(missing))).isEmpty();
        assertThat(cache.getAll(List.of())).isEmpty();
    }

    @Test
    void cacheGetAllTreatsCorruptEntryAsMissAndEvictsIt() {
        String dk = unique("dk");
        redis.opsForValue().set(DeviceIdentityCache.key(dk), "{not json");

        assertThat(cache.getAll(List.of(dk))).isEmpty();
        assertThat(redis.hasKey(DeviceIdentityCache.key(dk))).isFalse();
    }

    @Test
    void findAllServesCachedFromCacheAndLoadsOnlyMissesFromDatabaseThenFillsCache() {
        // 缓存里放一个与数据库无关的身份（租户 99）：能原样取回说明命中缓存、没有回源
        TestDevice fromDb = createDevice(defaultTenantId(), "ENABLED");
        DeviceIdentity cached = new DeviceIdentity(unique("dk"), 99, "ENABLED", "h", "s", "pk", "ACTIVE");
        cache.put(cached);
        String unknown = unique("dk");

        Map<String, DeviceIdentity> found = lookup.findAll(List.of(cached.deviceKey(), fromDb.deviceKey(), unknown));

        assertThat(found).containsOnlyKeys(cached.deviceKey(), fromDb.deviceKey());
        assertThat(found.get(cached.deviceKey())).isEqualTo(cached);
        assertThat(found.get(fromDb.deviceKey()).tenantId()).isEqualTo(fromDb.tenantId());
        assertThat(cache.get(fromDb.deviceKey())).contains(found.get(fromDb.deviceKey()));
        assertThat(cache.get(unknown)).isEmpty();
    }

    @Test
    void findAllFallsBackToDatabaseWhenCacheBatchReadFails() {
        TestDevice device = createDevice(defaultTenantId(), "ENABLED");
        DeviceIdentityCache failing = org.mockito.Mockito.mock(DeviceIdentityCache.class);
        org.mockito.Mockito.when(failing.getAll(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        DeviceIdentityLookup degraded = new DeviceIdentityLookup(failing, identityMapper);

        Map<String, DeviceIdentity> found = degraded.findAll(List.of(device.deviceKey()));

        assertThat(found).containsOnlyKeys(device.deviceKey());
        // 缓存不可用时不应再逐条尝试回填
        org.mockito.Mockito.verify(failing, org.mockito.Mockito.never())
                .put(org.mockito.ArgumentMatchers.any());
    }
}
