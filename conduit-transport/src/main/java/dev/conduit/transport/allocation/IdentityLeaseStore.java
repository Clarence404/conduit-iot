package dev.conduit.transport.allocation;

import java.time.Duration;
import java.util.List;

/**
 * 身份池的共享状态存储：身份租约（每个 MQTT 身份一把带过期的锁）、实例心跳（存活实例集合）与身份池大小。
 * 分配器据此在多个 transport 实例间分摊身份。所有方法在 Redis 不可用时抛出 Spring 的
 * {@code DataAccessException}，由调用方决定降级策略。
 */
public interface IdentityLeaseStore {

    /** 本实例 id，写入租约值与心跳成员，用于归属比对。 */
    String instanceId();

    /** 抢占身份租约：键不存在则写入本实例 id；键已是本实例 id 则续期并返回 true；被他人持有返回 false。 */
    boolean tryAcquire(int identity);

    /** 值仍为本实例 id 时续期；已过期或被他人接管返回 false。 */
    boolean renew(int identity);

    /** 值仍为本实例 id 时删除租约，让其他实例可立即接管；否则返回 false 且不改动。 */
    boolean release(int identity);

    /**
     * 上报本实例心跳并返回存活实例 id：以 Redis 服务器时间为准，写入本实例、清除超过 memberTtl 未心跳的成员。
     * 用服务器时间而非本机时间，避免各实例时钟偏差影响成员判定。
     *
     * @return 存活实例 id，按字典序升序（各实例据此算出一致的配额位置），总是包含本实例
     */
    List<String> heartbeat(Duration memberTtl);

    /** 把本实例从存活集合中移除（正常停止时调用，让其他实例尽快重新分摊）。 */
    void leave();

    /** 读取身份池大小；尚未设置时以 SETNX 写入 initial 并返回实际生效值（并发写入时以先写者为准）。 */
    int poolSize(int initial);
}
