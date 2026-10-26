package dev.conduit.server.device;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import dev.conduit.server.presence.PresenceStore;
import dev.conduit.server.organization.OrganizationService;
import dev.conduit.server.organization.SiteMapper;
import dev.conduit.server.product.ProductMapper;
import dev.conduit.server.product.ProductService;
import dev.conduit.server.security.KeyGenerator;
import dev.conduit.server.security.SecretHasher;
import dev.conduit.server.telemetry.LatestValueStore;
import dev.conduit.server.web.ApiException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceServiceTest {

    @Test
    void deleteSucceedsAndStillCleansOtherKeysWhenRedisFails() {
        DeviceMapper devices = mock(DeviceMapper.class);
        DeviceIdentityCache identityCache = mock(DeviceIdentityCache.class);
        LatestValueStore latest = mock(LatestValueStore.class);
        PresenceStore presence = mock(PresenceStore.class);
        DeviceService service = new DeviceService(devices, mock(ProductMapper.class), mock(ProductService.class),
                mock(KeyGenerator.class), mock(SecretHasher.class), identityCache, latest, presence, Clock.systemUTC(),
                mock(SiteMapper.class), mock(OrganizationService.class));

        Device d = new Device();
        d.setId(7L);
        d.setDeviceKey("dk1");
        when(devices.selectOne(any())).thenReturn(d);
        doThrow(new RedisConnectionFailureException("down")).when(latest).delete("dk1");

        assertThatCode(() -> service.delete("dk1")).doesNotThrowAnyException();

        var order = inOrder(devices, identityCache, latest, presence);
        order.verify(devices).deleteById(7L);
        order.verify(identityCache).evict("dk1");
        order.verify(latest).delete("dk1");
        order.verify(presence).delete("dk1");
    }

    @Test
    void onlineFilterReturns503WhenRedisIsDown() {
        // 单元测试没有 Spring 容器，手动登记实体元数据，Lambda 查询条件才能解析列名
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Device.class);
        DeviceMapper devices = mock(DeviceMapper.class);
        PresenceStore presence = mock(PresenceStore.class);
        DeviceService service = new DeviceService(devices, mock(ProductMapper.class), mock(ProductService.class),
                mock(KeyGenerator.class), mock(SecretHasher.class), mock(DeviceIdentityCache.class),
                mock(LatestValueStore.class), presence, Clock.systemUTC(), mock(SiteMapper.class),
                mock(OrganizationService.class));
        Device d = new Device();
        d.setId(1L);
        d.setDeviceKey("dk1");
        when(devices.selectList(any())).thenReturn(List.of(d));
        when(presence.getAll(anyList())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> service.list(new DeviceFilter(null, null, null, true, null, null, null), 1, 10))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(503);
                    assertThat(e.getMessage()).isEqualTo("在线状态暂不可用");
                });
    }

    @Test
    void reportedFilterReturns503WhenLastReportReadFails() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Device.class);
        DeviceMapper devices = mock(DeviceMapper.class);
        LatestValueStore latest = mock(LatestValueStore.class);
        DeviceService service = new DeviceService(devices, mock(ProductMapper.class), mock(ProductService.class),
                mock(KeyGenerator.class), mock(SecretHasher.class), mock(DeviceIdentityCache.class),
                latest, mock(PresenceStore.class), Clock.systemUTC(), mock(SiteMapper.class),
                mock(OrganizationService.class));
        Device d = new Device();
        d.setId(1L);
        d.setDeviceKey("dk1");
        when(devices.selectList(any())).thenReturn(List.of(d));
        when(latest.lastReportTimes(anyList())).thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> service.list(
                new DeviceFilter(null, null, null, null, DeviceFilter.Reported.RECENT, null, null), 1, 10))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(503);
                    assertThat(e.getMessage()).isEqualTo("在线状态暂不可用");
                });
    }
}
