package dev.conduit.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 组装各租户产品的模拟设备：平台管理员密码为空时什么也不做（只模拟演示产品）；否则登录平台管理员，
 * 经 {@link TenantDiscovery} 发现产品，为每台设备配上按物模型生成读数的 {@link ThingModelReadingSource}。
 */
public final class SimulationPlanner {

    private static final Logger log = LoggerFactory.getLogger(SimulationPlanner.class);

    private SimulationPlanner() {
    }

    /**
     * 各租户产品的模拟设备。
     *
     * @param clients         创建平台管理员客户端（密码为空时不会调用）
     * @param demoProductKeys 演示产品 Key，发现时跳过
     * @return 设备列表；未开启多租户模拟或平台管理员登录失败时为空列表（登录失败记 WARN）
     */
    public static List<SimulatedDevice> tenantDevices(SimulatorConfig config, Supplier<ConduitApiClient> clients,
                                                      Set<String> demoProductKeys) {
        if (!config.multiTenant()) {
            log.info("SIM_PLATFORM_PASSWORD is empty: simulating only the demo product '{}'", config.productName());
            return List.of();
        }
        try (ConduitApiClient platform = clients.get()) {
            return plan(config, platform, demoProductKeys);
        }
    }

    /** 用平台管理员客户端发现并组装设备；登录失败返回空列表。 */
    private static List<SimulatedDevice> plan(SimulatorConfig config, ConduitApiClient platform,
                                              Set<String> demoProductKeys) {
        try {
            platform.login(config.platformUsername(), config.platformPassword());
        } catch (RuntimeException e) {
            log.warn("Platform admin {} cannot log in, simulating only the demo product: {}",
                    config.platformUsername(), e.getMessage());
            return List.of();
        }
        ZoneId zone = ZoneId.systemDefault();
        List<SimulatedDevice> devices = new ArrayList<>();
        for (TenantDiscovery.DiscoveredProduct product : TenantDiscovery.discover(platform, config.tenants(),
                config.maxDevicesPerProduct(), demoProductKeys, config.excludedProducts())) {
            for (DeviceCredentials credentials : product.devices()) {
                devices.add(new SimulatedDevice(credentials, product.format(),
                        new ThingModelReadingSource(product.properties(), credentials.deviceKey(), zone)));
            }
            log.info("Simulating {} devices of product '{}' ({}) in tenant {} with {} payloads",
                    product.devices().size(), product.productName(), product.productKey(), product.tenantCode(),
                    product.format().codec());
        }
        return devices;
    }
}
