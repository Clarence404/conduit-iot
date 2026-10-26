package dev.conduit.server.thingmodel;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.conduit.server.product.Product;
import dev.conduit.server.tenant.system.ProductLookupMapper;
import dev.conduit.server.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ThingModelCacheTest {

    ProductLookupMapper products = mock(ProductLookupMapper.class);
    ProductPropertyMapper properties = mock(ProductPropertyMapper.class);
    AtomicLong nanos = new AtomicLong();
    Ticker ticker = nanos::get;
    ThingModelCache cache;

    @BeforeEach
    void setUp() {
        cache = new ThingModelCache(products, properties, ticker);
        Product p = new Product();
        p.setId(10L);
        p.setTenantId(7L);
        p.setProductKey("pk");
        p.setCodec("flat-json");
        when(products.findByKeyAnyTenant("pk")).thenReturn(p);
        ProductProperty ua = new ProductProperty();
        ua.setIdentifier("Ua");
        ua.setName("A 相电压");
        ua.setDataType(DataType.DOUBLE);
        when(properties.selectList(any())).thenReturn(List.of(ua));
    }

    @Test
    void loadsOnceAndServesFromCache() {
        ProductProfile first = cache.find("pk").orElseThrow();
        cache.find("pk");

        assertThat(first.codec()).isEqualTo("flat-json");
        assertThat(first.properties()).containsOnlyKeys("Ua");
        assertThat(first.properties().get("Ua").name()).isEqualTo("A 相电压");
        verify(products, times(1)).findByKeyAnyTenant("pk");
    }

    @Test
    void loadsPropertiesUnderTheOwningTenant() {
        AtomicReference<Long> tenantSeen = new AtomicReference<>();
        when(properties.selectList(any())).thenAnswer(i -> {
            tenantSeen.set(TenantContext.get());
            return List.of();
        });

        assertThat(cache.find("pk")).map(ProductProfile::hasModel).contains(false);
        assertThat(tenantSeen.get()).isEqualTo(7L);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void missingProductIsNotCached() {
        assertThat(cache.find("ghost")).isEmpty();
        assertThat(cache.find("ghost")).isEmpty();

        verify(products, times(2)).findByKeyAnyTenant("ghost");
    }

    @Test
    void expiresSixtySecondsAfterWrite() {
        cache.find("pk");
        nanos.addAndGet(Duration.ofSeconds(59).toNanos());
        cache.find("pk");
        verify(products, times(1)).findByKeyAnyTenant("pk");

        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        cache.find("pk");
        verify(products, times(2)).findByKeyAnyTenant("pk");
    }

    @Test
    void evictForcesReload() {
        cache.find("pk");
        cache.evict("pk");
        cache.find("pk");

        verify(products, times(2)).findByKeyAnyTenant("pk");
    }

    @Test
    void databaseFailurePropagatesUnchanged() {
        when(products.findByKeyAnyTenant("pk")).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> cache.find("pk")).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
