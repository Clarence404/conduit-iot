package dev.conduit.transport.allocation;

import dev.conduit.transport.support.TestBroker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** RedisIdentityLeaseStore 集成测试（真实 Redis）：身份租约的归属校验与过期、实例心跳计数、身份池大小的一次性初始化。 */
class RedisIdentityLeaseStoreIT {

    static final String MEMBERS_KEY = "conduit:transport:members";
    static final String POOL_SIZE_KEY = "conduit:transport:pool-size";

    final StringRedisTemplate redis = TestBroker.redis();
    final Duration leaseTtl = Duration.ofSeconds(5);

    RedisIdentityLeaseStore store(Duration ttl, String id) {
        return new RedisIdentityLeaseStore(redis, ttl, id);
    }

    /** 前后都清：分配键是固定名，其他 IT（如 TransportApplicationIT 的上下文）可能留下 pool-size 等值。 */
    @BeforeEach
    @AfterEach
    void cleanKeys() {
        redis.delete(MEMBERS_KEY);
        redis.delete(POOL_SIZE_KEY);
        for (int i : new int[]{903, 904}) {
            redis.delete(RedisIdentityLeaseStore.identityKey(i));
        }
    }

    @Test
    void acquireRenewReleaseAreOwnerChecked() {
        RedisIdentityLeaseStore a = store(leaseTtl, "instance-a");
        RedisIdentityLeaseStore b = store(leaseTtl, "instance-b");
        String key = RedisIdentityLeaseStore.identityKey(903);

        assertThat(a.tryAcquire(903)).isTrue();
        assertThat(b.tryAcquire(903)).isFalse();
        assertThat(a.tryAcquire(903)).as("the holder re-acquires its own lease").isTrue();

        assertThat(b.renew(903)).isFalse();
        assertThat(b.release(903)).isFalse();
        assertThat(redis.opsForValue().get(key)).isEqualTo("instance-a");

        assertThat(a.renew(903)).isTrue();
        assertThat(a.release(903)).isTrue();
        assertThat(b.tryAcquire(903)).isTrue();
        assertThat(redis.opsForValue().get(key)).isEqualTo("instance-b");
    }

    @Test
    void leaseExpiresAfterTtl() throws Exception {
        RedisIdentityLeaseStore a = store(Duration.ofMillis(300), "instance-a");
        RedisIdentityLeaseStore b = store(Duration.ofMillis(300), "instance-b");

        assertThat(a.tryAcquire(904)).isTrue();
        Thread.sleep(400);

        assertThat(b.tryAcquire(904)).isTrue();
        assertThat(a.renew(904)).isFalse();
    }

    @Test
    void heartbeatCountsLiveMembers() {
        RedisIdentityLeaseStore a = store(leaseTtl, "instance-a");
        RedisIdentityLeaseStore b = store(leaseTtl, "instance-b");

        assertThat(a.heartbeat(Duration.ofSeconds(5))).isEqualTo(1);
        assertThat(b.heartbeat(Duration.ofSeconds(5))).isEqualTo(2);
        assertThat(a.heartbeat(Duration.ofSeconds(5))).as("a repeated heartbeat does not double count").isEqualTo(2);
    }

    @Test
    void leaveRemovesTheMember() {
        RedisIdentityLeaseStore a = store(leaseTtl, "instance-a");
        RedisIdentityLeaseStore b = store(leaseTtl, "instance-b");
        a.heartbeat(Duration.ofSeconds(5));
        b.heartbeat(Duration.ofSeconds(5));

        a.leave();

        assertThat(b.heartbeat(Duration.ofSeconds(5))).isEqualTo(1);
    }

    @Test
    void staleMembersAreNotCounted() throws Exception {
        RedisIdentityLeaseStore a = store(leaseTtl, "instance-a");
        RedisIdentityLeaseStore b = store(leaseTtl, "instance-b");
        Duration memberTtl = Duration.ofMillis(300);

        a.heartbeat(memberTtl);
        Thread.sleep(400);

        assertThat(b.heartbeat(memberTtl)).isEqualTo(1);
    }

    @Test
    void poolSizeInitialisedOnceThenRead() {
        RedisIdentityLeaseStore a = store(leaseTtl, "instance-a");

        assertThat(a.poolSize(64)).isEqualTo(64);
        redis.opsForValue().set(POOL_SIZE_KEY, "128");
        assertThat(a.poolSize(64)).isEqualTo(128);
        assertThat(a.poolSize(64)).isEqualTo(128);
    }
}
