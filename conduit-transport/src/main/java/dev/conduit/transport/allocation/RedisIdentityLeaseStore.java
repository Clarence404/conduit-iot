package dev.conduit.transport.allocation;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * {@link IdentityLeaseStore} 的 Redis 实现。
 * <ul>
 *   <li>租约：键 {@code conduit:transport:id:{i}}，值为实例 id；抢占、续期、释放用 Lua 先比对 id，
 *       保证租约过期被他人接管后，旧持有者的续期与释放都不生效。</li>
 *   <li>心跳：有序集合 {@code conduit:transport:members}，分值为 Redis 服务器毫秒时间。</li>
 *   <li>池大小：字符串键 {@code conduit:transport:pool-size}。</li>
 * </ul>
 */
public class RedisIdentityLeaseStore implements IdentityLeaseStore {

    static final String IDENTITY_KEY_PREFIX = "conduit:transport:id:";
    static final String MEMBERS_KEY = "conduit:transport:members";
    static final String POOL_SIZE_KEY = "conduit:transport:pool-size";

    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>(
            "local v = redis.call('GET', KEYS[1]) "
                    + "if v == ARGV[1] then redis.call('PEXPIRE', KEYS[1], ARGV[2]) return 1 end "
                    + "if not v then redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2]) return 1 end "
                    + "return 0", Long.class);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) end "
                    + "return 0", Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
            Long.class);
    /** 心跳：服务器时间戳写入本实例，清除严格早于 now - ttl 的成员，返回存活数。 */
    private static final DefaultRedisScript<Long> HEARTBEAT = new DefaultRedisScript<>(
            "local t = redis.call('TIME') "
                    + "local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000) "
                    + "redis.call('ZADD', KEYS[1], now, ARGV[1]) "
                    + "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. (now - tonumber(ARGV[2]))) "
                    + "return redis.call('ZCARD', KEYS[1])", Long.class);

    private final StringRedisTemplate redis;
    private final Duration leaseTtl;
    private final String instanceId;

    public RedisIdentityLeaseStore(StringRedisTemplate redis, Duration leaseTtl, String instanceId) {
        this.redis = redis;
        this.leaseTtl = leaseTtl;
        this.instanceId = instanceId;
    }

    /** 身份 i 的租约键。 */
    static String identityKey(int identity) {
        return IDENTITY_KEY_PREFIX + identity;
    }

    @Override
    public String instanceId() {
        return instanceId;
    }

    @Override
    public boolean tryAcquire(int identity) {
        Long result = redis.execute(ACQUIRE, List.of(identityKey(identity)), instanceId,
                Long.toString(leaseTtl.toMillis()));
        return Long.valueOf(1).equals(result);
    }

    @Override
    public boolean renew(int identity) {
        Long result = redis.execute(RENEW, List.of(identityKey(identity)), instanceId,
                Long.toString(leaseTtl.toMillis()));
        return Long.valueOf(1).equals(result);
    }

    @Override
    public boolean release(int identity) {
        Long result = redis.execute(RELEASE, List.of(identityKey(identity)), instanceId);
        return Long.valueOf(1).equals(result);
    }

    @Override
    public int heartbeat(Duration memberTtl) {
        Long result = redis.execute(HEARTBEAT, List.of(MEMBERS_KEY), instanceId,
                Long.toString(memberTtl.toMillis()));
        return result == null ? 0 : result.intValue();
    }

    @Override
    public void leave() {
        redis.opsForZSet().remove(MEMBERS_KEY, instanceId);
    }

    @Override
    public int poolSize(int initial) {
        String value = redis.opsForValue().get(POOL_SIZE_KEY);
        if (value == null) {
            redis.opsForValue().setIfAbsent(POOL_SIZE_KEY, Integer.toString(initial));
            // 再读一次：并发写入时以先写者为准，所有实例看到同一个值
            value = redis.opsForValue().get(POOL_SIZE_KEY);
        }
        return value == null ? initial : Integer.parseInt(value.trim());
    }

    /**
     * 生成实例 id：主机名-进程号-8 位随机十六进制。主机名取不到时用 unknown-host。
     * 同一主机上的多个进程、同一进程的多次调用都得到不同的 id。
     */
    public static String newInstanceId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown-host";
        }
        return host + "-" + ProcessHandle.current().pid() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
