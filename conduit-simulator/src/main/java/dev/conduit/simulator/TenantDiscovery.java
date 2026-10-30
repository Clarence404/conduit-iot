package dev.conduit.simulator;

import dev.conduit.simulator.ConduitApiClient.DeviceSummary;
import dev.conduit.simulator.ConduitApiClient.ProductSummary;
import dev.conduit.simulator.ConduitApiClient.TenantSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 多租户发现（V0.2-R spec 5.1，启动时一次）：平台管理员列出启用中的租户，按 SIM_TENANTS 过滤后逐个进入，
 * 跳过排除的产品后，取物模型非空、解码器可生成的产品及其启用设备（每产品最多 maxDevicesPerProduct 台），重置这些设备的密钥，再退出租户。
 * 任一租户或产品失败只记 WARN，跳过它继续处理其他的。
 */
public final class TenantDiscovery {

    private static final Logger log = LoggerFactory.getLogger(TenantDiscovery.class);

    private TenantDiscovery() {
    }

    /**
     * 发现到的一个租户产品。
     *
     * @param format     按产品解码器选定的上报格式
     * @param properties 产品物模型的属性定义
     * @param devices    已重置密钥的设备凭据
     */
    public record DiscoveredProduct(String tenantCode, String productKey, String productName, PayloadFormat format,
                                    List<PropertyDef> properties, List<DeviceCredentials> devices) {
    }

    /**
     * 发现要模拟的租户产品。
     *
     * @param platform             已用平台管理员登录的客户端（发现过程中会进入、退出租户）
     * @param filter               模拟哪些租户
     * @param maxDevicesPerProduct 每产品最多模拟的设备数
     * @param skipProductKeys      跳过的产品（演示产品，由 DemoProvisioner 处理）
     * @param excludedProducts     排除的产品名称或 productKey（SIM_EXCLUDE_PRODUCTS），在读物模型与重置密钥之前判断
     * @return 至少有一台设备的产品，按租户与产品的列表顺序
     */
    public static List<DiscoveredProduct> discover(ConduitApiClient platform, TenantFilter filter,
                                                   int maxDevicesPerProduct, Set<String> skipProductKeys,
                                                   Set<String> excludedProducts) {
        List<DiscoveredProduct> result = new ArrayList<>();
        for (TenantSummary tenant : platform.listActiveTenants()) {
            if (!filter.includes(tenant.code())) {
                log.debug("Tenant {} is not in SIM_TENANTS, skipped", tenant.code());
                continue;
            }
            try {
                platform.enterTenant(tenant.code());
            } catch (RuntimeException e) {
                log.warn("Cannot enter tenant {} ({}), skipping it: {}", tenant.code(), tenant.name(), e.getMessage());
                continue;
            }
            try {
                List<DiscoveredProduct> products = discoverTenant(platform, tenant, maxDevicesPerProduct,
                        skipProductKeys, excludedProducts);
                result.addAll(products);
                log.info("Tenant {} ({}): {} products with {} devices to simulate", tenant.code(), tenant.name(),
                        products.size(), products.stream().mapToInt(p -> p.devices().size()).sum());
            } catch (RuntimeException e) {
                log.warn("Discovery failed in tenant {} ({}), skipping it: {}", tenant.code(), tenant.name(),
                        e.getMessage());
            } finally {
                if (!leaveTenant(platform, tenant)) {
                    log.warn("Cannot restore the platform admin identity after tenant {}, "
                            + "stopping discovery with {} products found", tenant.code(), result.size());
                    return result;
                }
            }
        }
        return result;
    }

    /**
     * 退出租户；退出失败时客户端仍持有代入令牌，会让后续租户全部 403，所以改为用平台管理员账号重新登录换回自己的令牌。
     *
     * @return 客户端已恢复为平台管理员身份；退出与重新登录都失败时为 false（调用方应停止发现）
     */
    private static boolean leaveTenant(ConduitApiClient platform, TenantSummary tenant) {
        try {
            platform.exitTenant();
            return true;
        } catch (RuntimeException e) {
            log.warn("Cannot exit tenant {}, logging in again as the platform admin: {}", tenant.code(),
                    e.getMessage());
        }
        try {
            platform.relogin();
            return true;
        } catch (RuntimeException e) {
            log.warn("Platform admin re-login failed: {}", e.getMessage());
            return false;
        }
    }

    /** 在已进入的租户内发现产品；单个产品失败只记 WARN 并跳过。 */
    private static List<DiscoveredProduct> discoverTenant(ConduitApiClient platform, TenantSummary tenant,
                                                          int maxDevicesPerProduct, Set<String> skipProductKeys,
                                                          Set<String> excludedProducts) {
        List<DiscoveredProduct> products = new ArrayList<>();
        for (ProductSummary product : platform.listProducts()) {
            if (skipProductKeys.contains(product.productKey())) {
                log.debug("Product {} is the demo product, simulated by the demo flow", product.productKey());
                continue;
            }
            if (excludedProducts.contains(product.name()) || excludedProducts.contains(product.productKey())) {
                log.info("Product {} ({}) in tenant {} is in SIM_EXCLUDE_PRODUCTS, not simulated and secrets untouched",
                        product.productKey(), product.name(), tenant.code());
                continue;
            }
            if (product.propertyCount() == 0) {
                log.debug("Product {} ({}) has no thing model, skipped", product.productKey(), product.name());
                continue;
            }
            Optional<PayloadFormat> format = PayloadFormat.fromCodec(product.codec());
            if (format.isEmpty()) {
                log.warn("Product {} ({}) uses codec {} which the simulator cannot produce, skipped",
                        product.productKey(), product.name(), product.codec());
                continue;
            }
            try {
                List<PropertyDef> properties = platform.properties(product.productKey());
                boolean pvModel = PointClassifier.isPvModel(properties);
                if (properties.stream().noneMatch(p -> PointClassifier.classify(p, pvModel) != PointKind.SKIP)) {
                    log.debug("Product {} has no property the simulator can generate, skipped", product.productKey());
                    continue;
                }
                List<DeviceCredentials> devices = new ArrayList<>();
                for (DeviceSummary device : platform.listEnabledDevices(product.productKey(), maxDevicesPerProduct)) {
                    try {
                        devices.add(platform.resetSecret(product.productKey(), device.deviceKey()));
                    } catch (RuntimeException e) {
                        // 已重置的设备旧密钥已失效，必须保留它们的新密钥；只跳过失败的这一台
                        log.warn("Cannot reset the secret of device {} (product {}), skipping the device: {}",
                                device.deviceKey(), product.productKey(), e.getMessage());
                    }
                }
                if (devices.isEmpty()) {
                    log.debug("Product {} has no enabled device, skipped", product.productKey());
                    continue;
                }
                products.add(new DiscoveredProduct(tenant.code(), product.productKey(), product.name(), format.get(),
                        properties, devices));
            } catch (RuntimeException e) {
                log.warn("Skipping product {} ({}) in tenant {}: {}", product.productKey(), product.name(),
                        tenant.code(), e.getMessage());
            }
        }
        return products;
    }
}
