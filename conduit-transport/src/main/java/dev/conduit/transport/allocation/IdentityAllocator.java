package dev.conduit.transport.allocation;

import dev.conduit.transport.adapter.ProtocolAdapter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

/**
 * transport 身份池分配器（多活 spec 3.3～3.5）：把 K 个 MQTT 身份按 Redis 租约分摊到 N 个存活实例，
 * 本实例只为自己持有的身份运行协议适配器。
 *
 * <p>每次检查（tick，默认每 2 秒）：写心跳读存活成员列表与 K → 按本实例在成员列表（字典序）中的位置计算精确配额
 * （{@link IdentityShares#quota}，各实例配额合计恰为 K、相差不超过 1）→ 续期全部持有的身份（失败立即停适配器）
 * → 检查断线或启动失败的适配器（续期确认仍持有后重连 / 到期重试）→ 不足配额时按偏好序抢占 → 超出配额且成员列表已稳定
 * rebalance-stable 时按偏好序从后往前让出一个。另有 renewTick 每 renew-interval 单独续期一次。
 *
 * <p>安全规则：任何续期失败（返回 false 或抛异常）都先把该身份移出持有表（使适配器的 stillValid 立即为 false），
 * 再在当前线程上同步停止适配器；让出时同样先停适配器、再释放租约。宁可短暂没人接，也不让两个实例同时连同一个 clientId。
 * 一轮续期中只要出现一次 Redis 异常，其余未续期的身份也立即停掉，不再逐个等待命令超时（否则整轮可能拖过 lease-ttl）。
 *
 * <p>线程：tick 与 renewTick 在同一条调度线程上串行执行，计数、时钟与告警状态只由它读写；持有表是并发 Map，
 * 供适配器线程（stillValid）、健康检查与指标读取。适配器的启动与断线重连在专用单线程执行器上进行，每个任务只尝试
 * 一次：失败后记下该身份的下次尝试时刻（1s 起翻倍、最多 10s），由之后的 tick 到期重新提交——不在执行器线程上
 * 循环重试，否则一个始终起不来的身份会占住唯一的线程，让本实例其他身份都无法启动。同一身份同一时刻最多一个
 * 排队或执行中的任务。停止则在发现问题的线程上同步执行，不排队，可打断进行中的启动。
 * 测试经包级构造函数注入同步执行器，直接调用 tick。
 *
 * <p>K 只增不减：读到更小的 K 时记 WARN 并忽略（缩小需要先排空会话，见 spec 第 4 节）。Redis 中的 K 不是正整数时
 * 记 ERROR（同一个值只记一次）并保持当前 K（尚无 K 时用 initial-pool-size），续期、抢占照常。
 *
 * <p>新旧版本不能混跑：旧版选主键 {@code conduit:transport:leader} 存在时（旧实例仍在运行或其锁未过期），
 * 不抢占任何身份，每分钟最多记一次 ERROR；已持有的身份照常续期。
 */
public class IdentityAllocator implements SmartLifecycle {

    static final Duration INITIAL_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(10);
    /** 扩容告警的最短间隔。 */
    static final Duration POOL_ALERT_INTERVAL = Duration.ofMinutes(10);
    /** 检测到旧版选主键时 ERROR 的最短间隔。 */
    static final Duration LEGACY_ALERT_INTERVAL = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(IdentityAllocator.class);

    private final IdentityLeaseStore store;
    private final IntFunction<ProtocolAdapter> adapterFactory;
    private final AllocationProperties props;
    private final Clock clock;
    /** 测试注入的执行器；为 null 时由 start() 创建自有的单线程执行器。 */
    private final Executor injectedExecutor;

    /** 持有的身份 → 本次持有的任期号。任期在每次抢到时重新分配，旧任期的启动循环据此放弃。 */
    private final Map<Integer, Long> held = new ConcurrentHashMap<>();
    /** 按身份序号缓存的适配器，每个序号只经 factory 创建一次，之后反复 start / stop。 */
    private final Map<Integer, ProtocolAdapter> adapters = new ConcurrentHashMap<>();
    /** 有启动或重连任务在排队或执行的身份，保证同一身份不会同时有两个任务。 */
    private final Set<Integer> pending = ConcurrentHashMap.newKeySet();
    /**
     * 等待（再次）启动的身份 → 任期、已失败次数与下次尝试时刻。抢到身份时以 0 次失败、立即到期写入，
     * 启动成功后删除；任期不符的条目视为过期、被忽略。由调度线程与适配器线程并发读写。
     */
    private final Map<Integer, StartRetry> startRetries = new ConcurrentHashMap<>();
    private final AtomicLong terms = new AtomicLong();

    private final Counter acquiredCounter;
    private final Counter releasedCounter;
    private final Counter lostCounter;

    private volatile int poolSize;
    private volatile int members;
    private volatile boolean running;

    // 以下字段只由调度线程（测试中为调用 tick 的线程）读写
    /** 当前 K 下本实例的偏好顺序，K 变化时重算。 */
    private List<Integer> preference = List.of();
    /**
     * 上一次心跳读到的成员列表及其开始的时刻，用于判断成员是否已稳定 rebalance-stable。
     * 按列表而非人数判断：一个实例下线、另一个同时加入时人数不变，但各实例的配额位置已变。
     */
    private List<String> lastMembers;
    private Instant membersSince;
    private Instant lastPoolAlert;
    /** 上一次被忽略的更小 K：同一个值只 WARN 一次。 */
    private int ignoredPoolSize = -1;
    /** 上一次拒绝的非法 K 原始值：同一个值只 ERROR 一次，读到合法值后清空。 */
    private String rejectedPoolSize;
    /** 上一次因旧版选主键记 ERROR 的时刻。 */
    private Instant lastLegacyAlert;
    /** Redis 是否处于失败状态：只在状态切换时打 WARN / INFO，其余失败降为 DEBUG。 */
    private boolean redisFailing;

    private ScheduledExecutorService scheduler;
    private ExecutorService ownedExecutor;

    /** 身份的待启动记录：任期 term 下已失败 failures 次，nextAttempt 起可再次尝试。 */
    private record StartRetry(long term, int failures, Instant nextAttempt) {
    }

    public IdentityAllocator(IdentityLeaseStore store, IntFunction<ProtocolAdapter> adapterFactory,
                             AllocationProperties props, Clock clock, MeterRegistry registry) {
        this(store, adapterFactory, props, clock, registry, null);
    }

    /** 测试用：注入执行适配器启停的执行器（如同步执行器 Runnable::run），使结果不依赖真实时间与线程调度。 */
    IdentityAllocator(IdentityLeaseStore store, IntFunction<ProtocolAdapter> adapterFactory,
                      AllocationProperties props, Clock clock, MeterRegistry registry, Executor adapterExecutor) {
        this.store = store;
        this.adapterFactory = adapterFactory;
        this.props = props;
        this.clock = clock;
        this.injectedExecutor = adapterExecutor;
        Gauge.builder("conduit.transport.identities.held", held, Map::size)
                .description("本实例持有的 MQTT 身份数").register(registry);
        Gauge.builder("conduit.transport.pool.size", this, a -> a.poolSize)
                .description("身份池大小 K").register(registry);
        Gauge.builder("conduit.transport.members", this, a -> a.members)
                .description("存活的 transport 实例数 N").register(registry);
        this.acquiredCounter = moved(registry, "acquire");
        this.releasedCounter = moved(registry, "release");
        this.lostCounter = moved(registry, "lost");
    }

    private static Counter moved(MeterRegistry registry, String reason) {
        return Counter.builder("conduit.transport.identities.moved").tag("reason", reason)
                .description("身份迁移次数：acquire 抢到、release 主动让出、lost 续期失败").register(registry);
    }

    /** 本实例当前持有的身份序号（升序快照）。 */
    public Set<Integer> held() {
        return new TreeSet<>(held.keySet());
    }

    /** 最近一次心跳读到的存活实例数 N（成员列表的长度）；尚未成功心跳时为 0。 */
    public int members() {
        return members;
    }

    /** 当前生效的身份池大小 K；尚未成功读取时为 0。 */
    public int poolSize() {
        return poolSize;
    }

    /**
     * 每个持有身份的适配器都已连接（供健康检查）。未持有任何身份时为 true：
     * 实例数多于 K 时的空闲实例、或等待再平衡的实例都是正常状态，不应被判为 DOWN。
     */
    public boolean allConnected() {
        for (int i : held.keySet()) {
            ProtocolAdapter adapter = adapters.get(i);
            if (adapter == null || !adapter.isConnected()) {
                return false;
            }
        }
        return true;
    }

    public String instanceId() {
        return store.instanceId();
    }

    /** 启动调度线程：立即检查一次，之后每 tick-interval 检查、每 renew-interval 续期。重复调用无效果。 */
    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        if (injectedExecutor == null) {
            ownedExecutor = Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().name("transport-adapter").daemon().factory());
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("transport-allocator").daemon().factory());
        scheduler.scheduleWithFixedDelay(guard("tick", this::tick), 0,
                props.tickInterval().toMillis(), TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(guard("renew", this::renewTick), props.renewInterval().toMillis(),
                props.renewInterval().toMillis(), TimeUnit.MILLISECONDS);
        log.info("Identity allocator started as instance {} (lease ttl={}, renew every {}, tick every {})",
                store.instanceId(), props.leaseTtl(), props.renewInterval(), props.tickInterval());
    }

    /**
     * 停止：先停调度（不再抢占或续期），再让全部持有失效并同步停止全部适配器，停掉自有执行器（打断进行中的启动），
     * 然后逐个释放租约（比对 id 后删除），最后离开存活集合。释放或离开失败只记 WARN，租约与心跳会自然过期。
     * 测试中未调用 start() 也可直接调用，用于验证清理过程。
     */
    @Override
    public synchronized void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            awaitQuietly(scheduler);
            scheduler = null;
        }
        List<Integer> toRelease = new ArrayList<>(held.keySet());
        held.clear();
        startRetries.clear();
        adapters.forEach((i, adapter) -> stopAdapter(i, adapter));
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
            awaitQuietly(ownedExecutor);
            ownedExecutor = null;
        }
        // shutdownNow 丢弃的排队任务不会执行到 finally，需在此清掉其占位，否则再次 start 后这些身份永远不会被提交启动
        pending.clear();
        for (int i : toRelease) {
            try {
                boolean released = store.release(i);
                releasedCounter.increment();
                log.info("Identity #{} {} on shutdown by instance {}", i,
                        released ? "released" : "was no longer held", store.instanceId());
            } catch (RuntimeException e) {
                log.warn("Could not release identity #{} on shutdown (it expires after {}): {}", i,
                        props.leaseTtl(), e.getMessage());
            }
        }
        try {
            store.leave();
        } catch (RuntimeException e) {
            log.warn("Could not leave the transport member set on shutdown (it expires after {}): {}",
                    props.memberTtl(), e.getMessage());
        }
        log.info("Identity allocator stopped on instance {}", store.instanceId());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 一次检查，按 spec 3.3 的五步执行。
     * 心跳或读取 K 出错时：按失败状态记日志，仍执行续期（让续期失败的身份停掉适配器），本轮不抢占、不让出。
     * 续期或抢占中出现 Redis 错误时同样跳过后续的抢占与让出，避免在 Redis 不稳定时迁移身份。
     */
    public void tick() {
        List<String> memberList;
        int k;
        try {
            memberList = store.heartbeat(props.memberTtl());
            k = readPoolSize();
        } catch (RuntimeException e) {
            redisFailed("heartbeat", e);
            renewAll();
            return;
        }
        redisRecovered();
        Instant now = clock.instant();
        updateMembers(memberList, now);
        updatePoolSize(k);
        alertIfPoolTooSmall(now);

        int quota = IdentityShares.quota(poolSize, memberList, store.instanceId());
        boolean renewOk = renewAll();
        superviseAdapters();
        if (!renewOk) {
            return;
        }
        if (held.size() < quota) {
            if (!legacyLeaderBlocks(now)) {
                acquireUpTo(quota);
            }
        } else if (held.size() > quota && isMembershipStable(now)) {
            releaseOne();
        }
    }

    /**
     * 读 Redis 中的 K。值不是正整数时（运维写错）不当作 Redis 故障：同一个值只记一次 ERROR，返回当前 K
     * （尚无 K 时为 initial-pool-size），本轮照常续期与抢占。Redis 不可达的异常照常抛出。
     */
    private int readPoolSize() {
        String bad;
        try {
            int k = store.poolSize(props.initialPoolSize());
            if (k >= 1) {
                rejectedPoolSize = null;
                return k;
            }
            bad = Integer.toString(k);
        } catch (InvalidPoolSizeException e) {
            bad = e.rawValue();
        }
        int keep = poolSize > 0 ? poolSize : props.initialPoolSize();
        if (!bad.equals(rejectedPoolSize)) {
            rejectedPoolSize = bad;
            log.error("Ignoring invalid identity pool size \"{}\" in Redis key conduit:transport:pool-size "
                    + "(must be a positive integer), keeping {}; set it to an integer >= the current size", bad, keep);
        }
        return keep;
    }

    /**
     * 旧版选主键存在时返回 true（本轮不抢占），每 {@link #LEGACY_ALERT_INTERVAL} 最多记一次 ERROR。
     * 检查出错按 Redis 故障处理，同样不抢占。
     */
    private boolean legacyLeaderBlocks(Instant now) {
        boolean present;
        try {
            present = store.legacyLeaderPresent();
        } catch (RuntimeException e) {
            redisFailed("legacy leader check", e);
            return true;
        }
        if (!present) {
            return false;
        }
        if (lastLegacyAlert == null || !now.isBefore(lastLegacyAlert.plus(LEGACY_ALERT_INTERVAL))) {
            lastLegacyAlert = now;
            log.error("Redis key conduit:transport:leader exists: an old single-active transport is still running "
                    + "or its lock has not expired. Instance {} acquires no identity until every old instance is "
                    + "stopped and the key expires; old and new versions must not run together",
                    store.instanceId());
        }
        return true;
    }

    /** 续期全部持有的身份，失败的立即停止适配器（见 renewAll）。生产上每 renew-interval 调用一次。 */
    public void renewTick() {
        renewAll();
    }

    /**
     * 成员列表变化时记下新的起始时刻；首次心跳也算一次变化，所以刚启动的实例要等 rebalance-stable 后才会让出。
     * 列表变化时记一行 INFO（人数与成员 id）。
     */
    private void updateMembers(List<String> memberList, Instant now) {
        if (!memberList.equals(lastMembers)) {
            if (lastMembers != null) {
                log.info("Transport members changed from {} to {} (instance {}): {}", lastMembers.size(),
                        memberList.size(), store.instanceId(), memberList);
            }
            lastMembers = List.copyOf(memberList);
            membersSince = now;
        }
        members = memberList.size();
    }

    /**
     * K 只增不减：变大时采用并重算偏好序；变小时同一个值只 WARN 一次并忽略。
     * 回退开关 initial-pool-size = 1 时 K 固定为 1：此时适配器用普通订阅（是否共享订阅在启动时按
     * initial-pool-size > 1 确定），若采用 Redis 里更大的 K，每个身份各有一份非共享订阅，消息会被重复投递 K 次。
     * Redis 里的值不为 1 时同一个值只 WARN 一次并忽略。
     */
    private void updatePoolSize(int k) {
        if (props.initialPoolSize() == 1) {
            if (poolSize != 1) {
                poolSize = 1;
                preference = List.of(0);
            }
            if (k != 1 && k != ignoredPoolSize) {
                ignoredPoolSize = k;
                log.warn("Ignoring identity pool size {} from Redis: initial-pool-size = 1 is the single-identity "
                        + "fallback with a non-shared subscription, keeping 1", k);
            }
            return;
        }
        int current = poolSize;
        if (k > current) {
            if (current > 0) {
                log.info("Identity pool size grew from {} to {}", current, k);
            }
            poolSize = k;
            preference = IdentityShares.preference(store.instanceId(), k);
            ignoredPoolSize = -1;
        } else if (k < current && k != ignoredPoolSize) {
            ignoredPoolSize = k;
            log.warn("Ignoring identity pool size decrease from {} to {}: shrinking is not supported, keeping {}",
                    current, k, current);
        }
    }

    /** 存活实例数超过 K × pool-alert-ratio 时 WARN，最多每 10 分钟一次，提示调大 K。 */
    private void alertIfPoolTooSmall(Instant now) {
        if (!IdentityShares.shouldAlert(members, poolSize, props.poolAlertRatio())) {
            return;
        }
        if (lastPoolAlert == null || !now.isBefore(lastPoolAlert.plus(POOL_ALERT_INTERVAL))) {
            lastPoolAlert = now;
            log.warn("Live transport members ({}) exceed {} of the identity pool ({}); "
                            + "raise conduit:transport:pool-size before adding instances",
                    members, props.poolAlertRatio(), poolSize);
        }
    }

    private boolean isMembershipStable(Instant now) {
        return membersSince != null && !now.isBefore(membersSince.plus(props.rebalanceStable()));
    }

    /**
     * 按序号升序续期全部持有的身份。返回 false（已被他人接管或租约过期）的身份视为丢失：
     * 移出持有表并立即同步停止其适配器，计入 moved{lost}。
     * 一旦某次续期抛异常（Redis 不可达或超时），其后尚未续期的身份也一并视为丢失、不再访问 Redis：
     * 否则每个身份各等一次命令超时，整轮可能拖过 lease-ttl，期间租约已可被他人抢到而本实例仍连着同一 clientId。
     * 若键其实仍属本实例，下一次检查会重新抢回并重连。
     *
     * @return 本轮是否没有 Redis 错误（用于决定是否继续抢占与让出）
     */
    private boolean renewAll() {
        String redisProblem = null;
        for (int i : new TreeSet<>(held.keySet())) {
            if (redisProblem != null) {
                lose(i, "not renewed after " + redisProblem);
                continue;
            }
            String problem;
            try {
                if (store.renew(i)) {
                    continue;
                }
                problem = "held by another instance or expired";
            } catch (RuntimeException e) {
                redisFailed("renew", e);
                redisProblem = "Redis error: " + e.getMessage();
                problem = redisProblem;
            }
            lose(i, problem);
        }
        return redisProblem == null;
    }

    /** 丢失身份：先使 stillValid 失效，再同步停止适配器，不经执行器排队；不再重试启动。 */
    private void lose(int i, String problem) {
        held.remove(i);
        startRetries.remove(i);
        lostCounter.increment();
        log.warn("Identity #{} lost by instance {} ({}), stopping its adapter", i, store.instanceId(), problem);
        ProtocolAdapter adapter = adapters.get(i);
        if (adapter != null) {
            stopAdapter(i, adapter);
        }
    }

    /**
     * 检查已续期的持有身份（本轮续期已确认仍持有）：待启动且已到下次尝试时刻的，提交一次启动；
     * 适配器断线需要重连的，在适配器线程上 stop 后提交一次启动。已有任务在排队或执行的身份跳过。
     * 任务执行前丢失身份时，启动会因 stillValid 为 false 而放弃。
     */
    private void superviseAdapters() {
        Instant now = clock.instant();
        for (Map.Entry<Integer, Long> entry : held.entrySet()) {
            int i = entry.getKey();
            long t = entry.getValue();
            ProtocolAdapter adapter = adapters.get(i);
            if (adapter == null || pending.contains(i)) {
                continue;
            }
            StartRetry retry = startRetries.get(i);
            if (retry != null && retry.term() == t) {
                if (!now.isBefore(retry.nextAttempt())) {
                    submitStart(i, adapter, t, false);
                }
            } else if (adapter.needsRestart()) {
                submitStart(i, adapter, t, true);
            }
        }
    }

    /**
     * 为身份 i（任期 t）提交一次启动任务；该身份已有任务在排队或执行时不提交（之后的 tick 会再检查）。
     *
     * @param reconnect 为 true 时先 stop 断线的适配器再启动（断线重连）
     */
    private void submitStart(int i, ProtocolAdapter adapter, long t, boolean reconnect) {
        if (!pending.add(i)) {
            return;
        }
        boolean submitted = submit(() -> {
            try {
                if (!stillHolds(i, t)) {
                    return;
                }
                if (reconnect) {
                    log.info("Reconnecting adapter for identity #{} on instance {}", i, store.instanceId());
                    adapter.stop();
                }
                startAdapter(i, adapter, t);
            } finally {
                pending.remove(i);
            }
        });
        if (!submitted) {
            pending.remove(i);
        }
    }

    /**
     * 按偏好顺序抢占空闲身份，直到持有数达到配额；每抢到一个就提交启动其适配器。
     * 空闲身份不够时（他人尚未让出超出配额的部分）本轮少抢，之后的检查再补。抢占出现 Redis 错误时停止本轮抢占。
     */
    private void acquireUpTo(int quota) {
        for (int i : preference) {
            if (held.size() >= quota) {
                return;
            }
            if (held.containsKey(i)) {
                continue;
            }
            boolean acquired;
            try {
                acquired = store.tryAcquire(i);
            } catch (RuntimeException e) {
                redisFailed("acquire", e);
                return;
            }
            if (!acquired) {
                continue;
            }
            long t = terms.incrementAndGet();
            ProtocolAdapter adapter = adapters.computeIfAbsent(i, adapterFactory::apply);
            // 先登记待启动：若该身份上一任期的任务仍在排队（本次提交被跳过），之后的 tick 会补交
            startRetries.put(i, new StartRetry(t, 0, clock.instant()));
            held.put(i, t);
            acquiredCounter.increment();
            log.info("Identity #{} acquired by instance {}", i, store.instanceId());
            submitStart(i, adapter, t, false);
        }
    }

    /**
     * 按偏好序从后往前让出一个持有的身份（spec 3.4）：先使其失效并同步停止适配器，再释放租约。
     * 释放出错只记日志，租约会在 lease-ttl 后过期；适配器已停，不会与接手者冲突。
     */
    private void releaseOne() {
        for (int idx = preference.size() - 1; idx >= 0; idx--) {
            int i = preference.get(idx);
            if (!held.containsKey(i)) {
                continue;
            }
            held.remove(i);
            startRetries.remove(i);
            ProtocolAdapter adapter = adapters.get(i);
            if (adapter != null) {
                stopAdapter(i, adapter);
            }
            releasedCounter.increment();
            try {
                store.release(i);
                log.info("Identity #{} released by instance {} to rebalance", i, store.instanceId());
            } catch (RuntimeException e) {
                redisFailed("release", e);
                log.info("Identity #{} given up by instance {}; its lease expires after {}", i,
                        store.instanceId(), props.leaseTtl());
            }
            return;
        }
    }

    /** 本实例是否仍以任期 t 持有身份 i：作为适配器 start 的 stillValid。 */
    private boolean stillHolds(int i, long t) {
        Long current = held.get(i);
        return current != null && current == t;
    }

    /**
     * 在仍以任期 t 持有身份 i 时尝试启动一次适配器（在适配器线程上执行）。
     * 成功：删除待启动记录，此前失败过则 INFO 记录失败次数；成功后若发现已失去身份，立即停止它。
     * 失败：记下失败次数与下次尝试时刻（1s 起翻倍、最多 10s），由之后的 tick 重新提交；首次失败 WARN，之后 DEBUG。
     * 失败时已失去身份则不再记录、不再重试。
     */
    private void startAdapter(int i, ProtocolAdapter adapter, long t) {
        if (!stillHolds(i, t)) {
            return;
        }
        StartRetry previous = startRetries.get(i);
        int failed = previous != null && previous.term() == t ? previous.failures() : 0;
        try {
            // 把持有校验交给适配器，使其在发出 CONNECT 与订阅前再确认一次，失去身份后不会再连上 broker
            adapter.start(() -> stillHolds(i, t));
        } catch (RuntimeException e) {
            if (!stillHolds(i, t)) {
                log.debug("Adapter {} start abandoned after identity #{} was given up: {}", adapter.name(), i,
                        e.getMessage());
                return;
            }
            Duration delay = backoff(failed);
            startRetries.compute(i, (k, old) -> stillHolds(i, t)
                    ? new StartRetry(t, failed + 1, clock.instant().plus(delay)) : old);
            if (failed == 0) {
                log.warn("Adapter {} for identity #{} failed to start, retrying in {} ms: {}", adapter.name(), i,
                        delay.toMillis(), e.getMessage());
            } else {
                log.debug("Adapter {} for identity #{} failed to start (attempt {}), retrying in {} ms: {}",
                        adapter.name(), i, failed + 1, delay.toMillis(), e.getMessage());
            }
            return;
        }
        startRetries.computeIfPresent(i, (k, old) -> old.term() == t ? null : old);
        if (!stillHolds(i, t)) {
            adapter.stop();
            return;
        }
        if (failed > 0) {
            log.info("Adapter {} for identity #{} connected after {} failed attempts", adapter.name(), i, failed);
        } else {
            log.info("Adapter {} for identity #{} connected", adapter.name(), i);
        }
    }

    /** 停止一个适配器，失败只记 WARN。 */
    private void stopAdapter(int i, ProtocolAdapter adapter) {
        try {
            adapter.stop();
        } catch (RuntimeException e) {
            log.warn("Adapter {} for identity #{} did not stop cleanly: {}", adapter.name(), i, e.getMessage());
        }
    }

    /** Redis 出错：从正常转为失败时 WARN，持续失败时 DEBUG。 */
    private void redisFailed(String what, RuntimeException e) {
        if (!redisFailing) {
            redisFailing = true;
            log.warn("Cannot reach Redis for identity allocation ({}): identities that cannot be renewed are "
                    + "stopped, none is acquired or released until Redis recovers: {}", what, e.getMessage());
        } else {
            log.debug("Redis still unreachable for identity allocation ({}): {}", what, e.getMessage());
        }
    }

    /** 心跳与读 K 成功：此前处于失败状态时记 INFO。 */
    private void redisRecovered() {
        if (redisFailing) {
            redisFailing = false;
            log.info("Redis is reachable again, instance {} resumes identity allocation", store.instanceId());
        }
    }

    private static Duration backoff(int attempt) {
        Duration delay = INITIAL_BACKOFF.multipliedBy(1L << Math.min(attempt, 10));
        return delay.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : delay;
    }

    /** 提交到适配器线程；执行器已停止或尚未创建时返回 false。 */
    private boolean submit(Runnable task) {
        Executor executor = injectedExecutor != null ? injectedExecutor : ownedExecutor;
        if (executor == null) {
            log.debug("Adapter task skipped, identity allocator is not running");
            return false;
        }
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            log.debug("Adapter task skipped, identity allocator is stopping");
            return false;
        }
    }

    /** 包装定时任务：异常只记日志，避免 scheduleWithFixedDelay 因异常停止后续执行。 */
    private Runnable guard(String what, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("Identity allocator {} failed unexpectedly", what, e);
            }
        };
    }

    private static void awaitQuietly(ExecutorService executor) {
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
