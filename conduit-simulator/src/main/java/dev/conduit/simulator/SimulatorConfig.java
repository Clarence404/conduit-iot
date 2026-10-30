package dev.conduit.simulator;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 模拟器配置，来自环境变量（或 simulator-local.properties）。
 *
 * @param apiUrl            conduit-server REST 地址
 * @param adminUsername     用于登录 REST 的管理员账号
 * @param adminPassword     管理员密码（必填）
 * @param mqttHost          EMQX 地址
 * @param mqttPort          EMQX MQTT 端口
 * @param productName       演示产品名称
 * @param deviceCount       模拟设备数量
 * @param interval          每台设备的上报周期
 * @param messagesPerDevice 每台设备发送条数；0 表示无限发送
 * @param payloadFormat     上报载荷格式，同时决定新建产品的解码器
 * @param ackTimeout        等待平台业务回执的超时时间，超时后原样重发
 * @param maxPending        每台设备最多保留的待确认消息数，达到后暂停产生新数据
 * @param excludedProducts  多租户发现时排除的产品（名称或 productKey），默认排除压测脚本的「压测产品」：
 *                          模拟器会重置所模拟设备的密钥，与压测脚本共用设备会互相作废对方的密钥
 */
public record SimulatorConfig(URI apiUrl, String adminUsername, String adminPassword,
                              String mqttHost, int mqttPort, String productName,
                              int deviceCount, Duration interval, int messagesPerDevice,
                              PayloadFormat payloadFormat,
                              String platformUsername, String platformPassword,
                              TenantFilter tenants, int maxDevicesPerProduct,
                              Duration ackTimeout, int maxPending, Set<String> excludedProducts) {

    public SimulatorConfig {
        excludedProducts = Set.copyOf(excludedProducts);
    }

    /** 本地联调用的配置文件（classpath 根目录，不入库），键名与环境变量相同。 */
    static final String LOCAL_PROPERTIES = "simulator-local.properties";

    /** 环境变量优先，其次是 classpath 上可选的 simulator-local.properties。 */
    public static SimulatorConfig load() {
        return fromEnv(merge(readLocalProperties(), System.getenv()));
    }

    /** 合并配置来源，同名键以 env 为准。 */
    static Map<String, String> merge(Map<String, String> local, Map<String, String> env) {
        Map<String, String> merged = new HashMap<>(local);
        merged.putAll(env);
        return merged;
    }

    /**
     * 从键值表构造配置，未提供的项取默认值。
     *
     * SIM_EXCLUDE_PRODUCTS 缺省为「压测产品」，显式配成空表示不排除。
     *
     * @throws IllegalStateException CONDUIT_ADMIN_PASSWORD 缺失或为空、SIM_PAYLOAD_FORMAT 非法、
     *                               SIM_MAX_DEVICES_PER_PRODUCT 小于 1
     * @throws NumberFormatException 数值类配置项格式错误
     */
    public static SimulatorConfig fromEnv(Map<String, String> env) {
        return new SimulatorConfig(
                URI.create(env.getOrDefault("CONDUIT_API_URL", "http://localhost:8080")),
                env.getOrDefault("CONDUIT_ADMIN_USERNAME", "admin"),
                require(env, "CONDUIT_ADMIN_PASSWORD"),
                env.getOrDefault("MQTT_HOST", "localhost"),
                Integer.parseInt(env.getOrDefault("MQTT_PORT", "1883")),
                env.getOrDefault("SIM_PRODUCT_NAME", "三相电表（演示）"),
                Integer.parseInt(env.getOrDefault("SIM_DEVICE_COUNT", "100")),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("SIM_INTERVAL_MS", "10000"))),
                Integer.parseInt(env.getOrDefault("SIM_MESSAGES_PER_DEVICE", "0")),
                PayloadFormat.parse(env.getOrDefault("SIM_PAYLOAD_FORMAT", "standard")),
                env.getOrDefault("SIM_PLATFORM_USERNAME", "sysadmin"),
                env.getOrDefault("SIM_PLATFORM_PASSWORD", ""),
                TenantFilter.parse(env.getOrDefault("SIM_TENANTS", "*")),
                atLeastOne(env, "SIM_MAX_DEVICES_PER_PRODUCT", 100),
                positiveDuration(env, "SIM_ACK_TIMEOUT_MS", 30_000),
                atLeastOne(env, "SIM_MAX_PENDING", 1000),
                commaSet(env.getOrDefault("SIM_EXCLUDE_PRODUCTS", "压测产品")));
    }

    /** 是否模拟各租户的产品：平台管理员密码非空时才开启。 */
    public boolean multiTenant() {
        return platformPassword != null && !platformPassword.isBlank();
    }

    /** 读取 classpath 上的本地配置文件；文件不存在返回空表。 */
    private static Map<String, String> readLocalProperties() {
        try (InputStream in = SimulatorConfig.class.getClassLoader().getResourceAsStream(LOCAL_PROPERTIES)) {
            if (in == null) {
                return Map.of();
            }
            Properties props = new Properties();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            Map<String, String> values = new HashMap<>();
            props.stringPropertyNames().forEach(k -> values.put(k, props.getProperty(k).trim()));
            return values;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + LOCAL_PROPERTIES, e);
        }
    }

    private static String require(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Environment variable " + name + " is required");
        }
        return value;
    }

    /** 按逗号拆分并去掉首尾空白，空项忽略。 */
    private static Set<String> commaSet(String value) {
        Set<String> items = new LinkedHashSet<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                items.add(part.trim());
            }
        }
        return items;
    }

    /** 读取不小于 1 的整数配置，缺省取 defaultValue。 */
    private static int atLeastOne(Map<String, String> env, String name, int defaultValue) {
        int value = Integer.parseInt(env.getOrDefault(name, Integer.toString(defaultValue)).trim());
        if (value < 1) {
            throw new IllegalStateException(name + " must be at least 1, got: " + value);
        }
        return value;
    }

    /** 读取大于 0 的毫秒时长。 */
    private static Duration positiveDuration(Map<String, String> env, String name, long defaultValue) {
        long value = Long.parseLong(env.getOrDefault(name, Long.toString(defaultValue)).trim());
        if (value < 1) {
            throw new IllegalStateException(name + " must be at least 1, got: " + value);
        }
        return Duration.ofMillis(value);
    }
}
