package dev.conduit.transport.allocation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.conduit.transport.adapter.ProtocolAdapter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IdentityAllocator 单元测试：内存版租约存储（可指定存活实例数 N、池大小 K、模拟 Redis 出错与其他实例持有身份）、
 * 记录启停的假适配器、可拨动的时钟与同步执行器，测试直接调用 tick / renewTick，不依赖真实时间。
 * 另以多个真实分配器共用一份内存 Redis（租约与心跳按测试时钟过期），验证收敛到精确配额。
 * 覆盖：按配额与偏好抢占、成员稳定后逐个让出、多实例收敛（含崩溃与重启）、续期失败或出错立即停适配器、K 只增不减、扩容告警限频、
 * 断线重连先续期、停止时释放全部租约并离开。
 */
class IdentityAllocatorTest {

    static final AllocationProperties PROPS = new AllocationProperties(4, Duration.ofSeconds(15),
            Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(15), Duration.ofSeconds(10), 0.5);

    final List<String> events = new ArrayList<>();
    final FakeStore store = new FakeStore("me", events);
    final Map<Integer, FakeAdapter> adapters = new HashMap<>();
    final List<Integer> factoryCalls = new ArrayList<>();
    final MutableClock clock = new MutableClock(Instant.parse("2026-11-19T00:00:00Z"));
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    final Logger logger = (Logger) LoggerFactory.getLogger(IdentityAllocator.class);

    IdentityAllocator allocator;

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
        allocator = new IdentityAllocator(store, i -> {
            factoryCalls.add(i);
            return adapters.computeIfAbsent(i, k -> new FakeAdapter(k, events));
        }, PROPS, clock, registry, Runnable::run);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @Test
    void singleInstanceTakesAllIdentities() {
        store.members = 1;
        store.pool = 4;

        allocator.tick();

        assertThat(allocator.held()).containsExactlyInAnyOrder(0, 1, 2, 3);
        assertThat(adapters).hasSize(4);
        adapters.values().forEach(a -> {
            assertThat(a.starts).isEqualTo(1);
            assertThat(a.connected).isTrue();
        });
        assertThat(allocator.allConnected()).isTrue();
        assertThat(allocator.members()).isEqualTo(1);
        assertThat(allocator.poolSize()).isEqualTo(4);
        assertThat(moved("acquire")).isEqualTo(4);
        assertThat(registry.get("conduit.transport.identities.held").gauge().value()).isEqualTo(4);
        assertThat(registry.get("conduit.transport.pool.size").gauge().value()).isEqualTo(4);
        assertThat(registry.get("conduit.transport.members").gauge().value()).isEqualTo(1);
    }

    @Test
    void acquiresOnlyUpToQuota() {
        store.members = 2;
        store.pool = 4;

        allocator.tick();

        List<Integer> preferred = IdentityShares.preference("me", 4).subList(0, 2);
        assertThat(allocator.held()).containsExactlyInAnyOrderElementsOf(preferred);
        assertThat(store.owners).containsOnlyKeys(preferred);
        assertThat(adapters.keySet()).containsExactlyInAnyOrderElementsOf(preferred);
    }

    @Test
    void releasesExtrasOnlyAfterStable() {
        store.members = 1;
        store.pool = 4;
        allocator.tick();
        assertThat(allocator.held()).hasSize(4);

        store.members = 2;
        allocator.tick();
        assertThat(allocator.held()).hasSize(4);

        clock.advance(Duration.ofSeconds(10));
        List<Integer> preference = IdentityShares.preference("me", 4);
        allocator.tick();
        assertThat(allocator.held()).hasSize(3).doesNotContain(preference.get(3));
        allocator.tick();
        assertThat(allocator.held()).containsExactlyInAnyOrder(preference.get(0), preference.get(1));
        allocator.tick();
        assertThat(allocator.held()).containsExactlyInAnyOrder(preference.get(0), preference.get(1));

        for (int released : List.of(preference.get(2), preference.get(3))) {
            assertThat(adapters.get(released).connected).isFalse();
            assertThat(adapters.get(released).stops).isEqualTo(1);
            assertThat(store.owners).doesNotContainKey(released);
            // 先停适配器再释放租约，避免他人接手时本实例仍连着同一 clientId
            assertThat(events.indexOf("stop " + released)).isLessThan(events.indexOf("release " + released));
        }
        assertThat(store.releases).containsExactly(preference.get(3), preference.get(2));
        assertThat(moved("release")).isEqualTo(2);
    }

    @Test
    void renewFailureStopsTheAdapter() {
        store.members = 1;
        store.pool = 4;
        allocator.tick();
        BooleanSupplier stillValid = adapters.get(1).lastStillValid;
        assertThat(stillValid.getAsBoolean()).isTrue();

        store.owners.put(1, "other");
        allocator.tick();

        assertThat(allocator.held()).containsExactlyInAnyOrder(0, 2, 3);
        assertThat(adapters.get(1).connected).isFalse();
        assertThat(adapters.get(1).stops).isEqualTo(1);
        assertThat(adapters.get(1).starts).isEqualTo(1);
        assertThat(stillValid.getAsBoolean()).isFalse();
        assertThat(moved("lost")).isEqualTo(1);
    }

    @Test
    void renewErrorStopsTheAdapter() {
        store.members = 1;
        store.pool = 4;
        allocator.tick();

        store.renewErrors.add(3);
        allocator.tick();

        assertThat(allocator.held()).containsExactlyInAnyOrder(0, 1, 2);
        assertThat(adapters.get(3).connected).isFalse();
        assertThat(adapters.get(3).stops).isEqualTo(1);
        assertThat(moved("lost")).isEqualTo(1);
    }

    @Test
    void renewErrorGivesUpRemainingIdentitiesWithoutWaitingOnRedis() {
        store.members = 1;
        store.pool = 4;
        allocator.tick();
        events.clear();

        store.renewErrors.add(1);
        allocator.renewTick();

        // 续期按序号升序：#0 成功，#1 出错，#2、#3 不再访问 Redis，直接停掉
        assertThat(events).filteredOn(e -> e.startsWith("renew")).containsExactly("renew 0");
        assertThat(allocator.held()).containsExactly(0);
        for (int i = 1; i < 4; i++) {
            assertThat(adapters.get(i).connected).isFalse();
        }
        assertThat(moved("lost")).isEqualTo(3);

        // 键仍属本实例：Redis 恢复后下一次检查重新抢回并重连
        store.renewErrors.clear();
        allocator.tick();
        assertThat(allocator.held()).containsExactlyInAnyOrder(0, 1, 2, 3);
        assertThat(adapters.get(1).connected).isTrue();
    }

    @Test
    void redisDownStopsAllAdaptersWithoutAcquiring() {
        store.members = 1;
        store.pool = 4;
        allocator.tick();

        store.down = true;
        allocator.tick();
        allocator.tick();

        assertThat(allocator.held()).isEmpty();
        adapters.values().forEach(a -> assertThat(a.connected).isFalse());
        assertThat(moved("lost")).isEqualTo(4);
        assertThat(warnings("Cannot reach Redis")).isEqualTo(1);

        store.down = false;
        allocator.tick();
        assertThat(allocator.held()).containsExactlyInAnyOrder(0, 1, 2, 3);
        assertThat(logs.list).anyMatch(e -> e.getLevel() == Level.INFO
                && e.getFormattedMessage().contains("Redis is reachable again"));
    }

    @Test
    void renewTickRenewsEveryHeldIdentity() {
        store.members = 1;
        store.pool = 4;
        allocator.tick();
        events.clear();
        store.owners.put(3, "other");

        allocator.renewTick();

        assertThat(events).contains("renew 0", "renew 1", "renew 2", "renew 3", "stop 3");
        assertThat(allocator.held()).containsExactlyInAnyOrder(0, 1, 2);
    }

    @Test
    void poolSizeIncreaseAddsIdentities() {
        store.members = 1;
        store.pool = 4;
        allocator.tick();

        store.pool = 8;
        allocator.tick();

        assertThat(factoryCalls).containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5, 6, 7);
        assertThat(allocator.held()).containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5, 6, 7);
        assertThat(allocator.poolSize()).isEqualTo(8);
        for (int i = 4; i < 8; i++) {
            assertThat(adapters.get(i).connected).isTrue();
        }
    }

    @Test
    void poolSizeDecreaseIsIgnored() {
        store.members = 1;
        store.pool = 8;
        allocator.tick();

        store.pool = 4;
        allocator.tick();
        allocator.tick();

        assertThat(warnings("pool size")).isEqualTo(1);
        assertThat(allocator.held()).containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5, 6, 7);
        assertThat(allocator.poolSize()).isEqualTo(8);
    }

    @Test
    void fallbackToSinglePoolIgnoresLargerStoredPoolSize() {
        AllocationProperties single = new AllocationProperties(1, Duration.ofSeconds(15), Duration.ofSeconds(5),
                Duration.ofSeconds(2), Duration.ofSeconds(15), Duration.ofSeconds(10), 0.5);
        IdentityAllocator fallback = new IdentityAllocator(store, i -> {
            factoryCalls.add(i);
            return adapters.computeIfAbsent(i, k -> new FakeAdapter(k, events));
        }, single, clock, new SimpleMeterRegistry(), Runnable::run);
        // 集群此前以 K = 64 运行过：Redis 里的 pool-size 仍是 64
        store.members = 1;
        store.pool = 64;

        fallback.tick();
        fallback.tick();

        assertThat(fallback.poolSize()).isEqualTo(1);
        assertThat(fallback.held()).containsExactly(0);
        assertThat(factoryCalls).containsExactly(0);
        assertThat(warnings("initial-pool-size = 1")).isEqualTo(1);
    }

    @Test
    void allConnectedWhenNothingIsHeld() {
        assertThat(allocator.allConnected()).isTrue();

        store.members = 8;
        store.pool = 4;
        for (int i = 0; i < 4; i++) {
            store.owners.put(i, "other");
        }
        allocator.tick();

        assertThat(allocator.held()).isEmpty();
        assertThat(allocator.allConnected()).isTrue();
    }

    @Test
    void notAllConnectedWhileAHeldAdapterIsDown() {
        store.members = 1;
        store.pool = 4;
        allocator.tick();

        adapters.get(2).dropConnection();

        assertThat(allocator.allConnected()).isFalse();
    }

    @Test
    void alertWhenMembersNearPool() {
        store.members = 33;
        store.pool = 64;

        allocator.tick();
        allocator.tick();
        assertThat(warnings("members")).isEqualTo(1);

        clock.advance(Duration.ofMinutes(9));
        allocator.tick();
        assertThat(warnings("members")).isEqualTo(1);

        clock.advance(Duration.ofMinutes(1));
        allocator.tick();
        assertThat(warnings("members")).isEqualTo(2);
    }

    @Test
    void identityZeroDutyFollowsHolder() {
        store.members = 1;
        store.pool = 4;

        allocator.tick();

        assertThat(factoryCalls).contains(0);
    }

    @Test
    void brokenAdapterIsRestartedOnlyWhileHeld() {
        store.members = 1;
        store.pool = 4;
        store.owners.put(0, "other");
        store.owners.put(1, "other");
        store.owners.put(3, "other");
        allocator.tick();
        assertThat(allocator.held()).containsExactly(2);
        FakeAdapter adapter = adapters.get(2);

        events.clear();
        adapter.dropConnection();
        allocator.tick();

        assertThat(adapter.stops).isEqualTo(1);
        assertThat(adapter.starts).isEqualTo(2);
        assertThat(adapter.connected).isTrue();
        assertThat(events.indexOf("renew 2")).isLessThan(events.indexOf("stop 2"));
        assertThat(events.indexOf("stop 2")).isLessThan(events.indexOf("start 2"));

        adapter.dropConnection();
        store.owners.put(2, "other");
        allocator.tick();

        assertThat(adapter.stops).isEqualTo(2);
        assertThat(adapter.starts).isEqualTo(2);
        assertThat(adapter.connected).isFalse();
        assertThat(allocator.held()).isEmpty();
    }

    @Test
    void stopReleasesEverything() {
        store.members = 1;
        store.pool = 4;
        allocator.tick();

        allocator.stop();

        adapters.values().forEach(a -> assertThat(a.connected).isFalse());
        assertThat(store.releases).containsExactlyInAnyOrder(0, 1, 2, 3);
        assertThat(store.owners).isEmpty();
        assertThat(store.leaves).isEqualTo(1);
        assertThat(allocator.held()).isEmpty();
        assertThat(events.lastIndexOf("stop 0")).isLessThan(events.indexOf("release 0"));
    }

    @Test
    void threeInstancesConvergeToExactQuotasAcrossCrashAndRestart() {
        SharedLeases redis = new SharedLeases(clock, PROPS.leaseTtl(), 16);
        Map<String, IdentityAllocator> live = new TreeMap<>();
        for (String id : List.of("a", "b", "c")) {
            live.put(id, member(redis, id, 16));
        }

        settle(redis, live, 60);
        assertExactQuotas(redis, live, 16);
        assertThat(holdings(live)).containsExactly(6, 5, 5);
        // 已收敛后不再迁移
        Map<String, Set<Integer>> before = heldBy(live);
        settle(redis, live, 30);
        assertThat(heldBy(live)).isEqualTo(before);

        // b 崩溃：不再续期、不再心跳，租约与心跳自然过期
        live.remove("b");
        settle(redis, live, 30);
        assertExactQuotas(redis, live, 16);
        assertThat(holdings(live)).containsExactly(8, 8);

        // b 以新的实例 id 重启
        live.put("b2", member(redis, "b2", 16));
        settle(redis, live, 60);
        assertExactQuotas(redis, live, 16);
        assertThat(holdings(live)).containsExactly(6, 5, 5);
    }

    @Test
    void tenInstancesSpreadSixtyFourIdentitiesEvenly() {
        SharedLeases redis = new SharedLeases(clock, PROPS.leaseTtl(), 64);
        Map<String, IdentityAllocator> live = new TreeMap<>();
        for (int n = 0; n < 10; n++) {
            live.put("t" + n, member(redis, "t" + n, 64));
        }

        settle(redis, live, 200);

        assertExactQuotas(redis, live, 64);
        assertThat(holdings(live)).containsExactly(7, 7, 7, 7, 6, 6, 6, 6, 6, 6);
    }

    /** 以共享的内存 Redis 创建一个实例的分配器，K 由 initial-pool-size 给定。 */
    private IdentityAllocator member(SharedLeases redis, String id, int pool) {
        AllocationProperties props = new AllocationProperties(pool, PROPS.leaseTtl(), PROPS.renewInterval(),
                PROPS.tickInterval(), PROPS.memberTtl(), PROPS.rebalanceStable(), 1.0);
        List<String> ignored = new ArrayList<>();
        return new IdentityAllocator(redis.storeFor(id), i -> new FakeAdapter(i, ignored), props, clock,
                new SimpleMeterRegistry(), Runnable::run);
    }

    /**
     * 每轮把时钟拨过一个 tick-interval，再让存活实例按 id 顺序各检查一次；
     * 每轮之后校验安全不变量：存活实例的持有互不重叠，且与 Redis 中的租约一致。
     */
    private void settle(SharedLeases redis, Map<String, IdentityAllocator> live, int rounds) {
        for (int r = 0; r < rounds; r++) {
            clock.advance(PROPS.tickInterval());
            live.values().forEach(IdentityAllocator::tick);
            Set<Integer> seen = new HashSet<>();
            live.forEach((id, a) -> {
                for (int i : a.held()) {
                    assertThat(seen.add(i)).as("identity #%d held twice", i).isTrue();
                    assertThat(redis.owner(i)).as("lease of #%d", i).isEqualTo(id);
                }
            });
        }
    }

    /** 全部 K 个身份恰好各被一个存活实例持有，且每个实例的持有数等于它按字典序位置算出的配额。 */
    private static void assertExactQuotas(SharedLeases redis, Map<String, IdentityAllocator> live, int pool) {
        List<Integer> all = new ArrayList<>();
        live.values().forEach(a -> all.addAll(a.held()));
        assertThat(all).hasSize(pool).containsExactlyInAnyOrderElementsOf(
                IntStream.range(0, pool).boxed().toList());
        List<String> sorted = new ArrayList<>(live.keySet());
        live.forEach((id, a) -> assertThat(a.held()).as("holdings of %s", id)
                .hasSize(IdentityShares.quota(pool, sorted, id)));
    }

    /** 各存活实例的持有数，按实例 id 升序。 */
    private static List<Integer> holdings(Map<String, IdentityAllocator> live) {
        return live.values().stream().map(a -> a.held().size()).toList();
    }

    private static Map<String, Set<Integer>> heldBy(Map<String, IdentityAllocator> live) {
        Map<String, Set<Integer>> result = new HashMap<>();
        live.forEach((id, a) -> result.put(id, a.held()));
        return result;
    }

    private double moved(String reason) {
        return registry.get("conduit.transport.identities.moved").tag("reason", reason).counter().count();
    }

    private long warnings(String text) {
        return logs.list.stream()
                .filter(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains(text))
                .count();
    }

    /**
     * 内存版租约存储：owners 模拟 Redis 中各身份的持有者，down 模拟 Redis 整体不可用。
     * 心跳返回本实例 "me" 与 members − 1 个 "peer-*"（字典序上 "me" 排第一，配额取最大的那一份）。
     */
    static final class FakeStore implements IdentityLeaseStore {
        final String id;
        final List<String> events;
        final Map<Integer, String> owners = new HashMap<>();
        final Set<Integer> renewErrors = new HashSet<>();
        final List<Integer> releases = new ArrayList<>();
        int members = 1;
        int pool = 4;
        int leaves;
        boolean down;

        FakeStore(String id, List<String> events) {
            this.id = id;
            this.events = events;
        }

        private void checkUp() {
            if (down) {
                throw new DataAccessResourceFailureException("redis down");
            }
        }

        @Override
        public String instanceId() {
            return id;
        }

        @Override
        public boolean tryAcquire(int identity) {
            checkUp();
            String owner = owners.get(identity);
            if (owner == null || owner.equals(id)) {
                owners.put(identity, id);
                return true;
            }
            return false;
        }

        @Override
        public boolean renew(int identity) {
            checkUp();
            if (renewErrors.contains(identity)) {
                throw new DataAccessResourceFailureException("renew timed out");
            }
            events.add("renew " + identity);
            return id.equals(owners.get(identity));
        }

        @Override
        public boolean release(int identity) {
            checkUp();
            events.add("release " + identity);
            releases.add(identity);
            return owners.remove(identity, id);
        }

        @Override
        public List<String> heartbeat(Duration memberTtl) {
            checkUp();
            List<String> live = new ArrayList<>(List.of(id));
            for (int p = 1; p < members; p++) {
                live.add(String.format("peer-%03d", p));
            }
            live.sort(null);
            return live;
        }

        @Override
        public void leave() {
            leaves++;
        }

        @Override
        public int poolSize(int initial) {
            checkUp();
            return pool;
        }
    }

    /**
     * 多个实例共用的内存版 Redis：租约按 lease-ttl 过期（到期时刻即失效，同 PX），
     * 心跳严格早于 now - member-ttl 的成员被清除（同 ZREMRANGEBYSCORE 开区间），时间取测试时钟。
     */
    static final class SharedLeases {
        final MutableClock clock;
        final Duration leaseTtl;
        final int pool;
        final Map<Integer, String> owners = new HashMap<>();
        final Map<Integer, Instant> expiry = new HashMap<>();
        final Map<String, Instant> heartbeats = new HashMap<>();

        SharedLeases(MutableClock clock, Duration leaseTtl, int pool) {
            this.clock = clock;
            this.leaseTtl = leaseTtl;
            this.pool = pool;
        }

        /** 当前仍有效的持有者；过期的租约先删除。 */
        String owner(int identity) {
            Instant at = expiry.get(identity);
            if (at != null && !clock.instant().isBefore(at)) {
                owners.remove(identity);
                expiry.remove(identity);
            }
            return owners.get(identity);
        }

        IdentityLeaseStore storeFor(String id) {
            return new IdentityLeaseStore() {
                @Override
                public String instanceId() {
                    return id;
                }

                @Override
                public boolean tryAcquire(int identity) {
                    String owner = owner(identity);
                    if (owner != null && !owner.equals(id)) {
                        return false;
                    }
                    owners.put(identity, id);
                    expiry.put(identity, clock.instant().plus(leaseTtl));
                    return true;
                }

                @Override
                public boolean renew(int identity) {
                    if (!id.equals(owner(identity))) {
                        return false;
                    }
                    expiry.put(identity, clock.instant().plus(leaseTtl));
                    return true;
                }

                @Override
                public boolean release(int identity) {
                    if (!id.equals(owner(identity))) {
                        return false;
                    }
                    owners.remove(identity);
                    expiry.remove(identity);
                    return true;
                }

                @Override
                public List<String> heartbeat(Duration memberTtl) {
                    Instant now = clock.instant();
                    heartbeats.put(id, now);
                    heartbeats.values().removeIf(t -> t.isBefore(now.minus(memberTtl)));
                    return new ArrayList<>(new TreeSet<>(heartbeats.keySet()));
                }

                @Override
                public void leave() {
                    heartbeats.remove(id);
                }

                @Override
                public int poolSize(int initial) {
                    return pool;
                }
            };
        }
    }

    /** 记录启停的假适配器；启动时检查 stillValid，失效则像真实适配器一样抛异常。 */
    static final class FakeAdapter implements ProtocolAdapter {
        final int identity;
        final List<String> events;
        int starts;
        int stops;
        volatile boolean connected;
        volatile boolean restartRequested;
        BooleanSupplier lastStillValid;

        FakeAdapter(int identity, List<String> events) {
            this.identity = identity;
            this.events = events;
        }

        @Override
        public String name() {
            return "fake-" + identity;
        }

        @Override
        public void start(BooleanSupplier stillValid) {
            starts++;
            lastStillValid = stillValid;
            if (!stillValid.getAsBoolean()) {
                throw new IllegalStateException("start no longer valid");
            }
            events.add("start " + identity);
            connected = true;
            restartRequested = false;
        }

        @Override
        public void stop() {
            stops++;
            events.add("stop " + identity);
            connected = false;
            restartRequested = false;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public boolean needsRestart() {
            return restartRequested;
        }

        void dropConnection() {
            connected = false;
            restartRequested = true;
        }
    }

    /** 可手动拨动的时钟。 */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
