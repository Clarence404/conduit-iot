package dev.conduit.server.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Supplier;

/** 当前线程绑定的租户。HTTP 请求由 JwtAuthFilter 绑定，后台任务用 runAs/callAs 显式绑定。 */
public final class TenantContext {

    private static final Logger log = LoggerFactory.getLogger(TenantContext.class);
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** 绑定当前线程的租户；请求结束或任务完成后必须 {@link #clear()}。 */
    public static void set(Long tenantId) {
        CURRENT.set(tenantId);
    }

    /** 当前线程绑定的租户 id；未绑定返回 null。 */
    public static Long get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 取当前租户 id；未绑定时抛 IllegalStateException（租户拦截器据此拒绝无租户上下文的查询）。 */
    public static long require() {
        Long id = CURRENT.get();
        if (id == null) {
            throw new IllegalStateException("No tenant bound to current thread");
        }
        return id;
    }

    /**
     * 以指定租户执行并返回结果，结束后恢复调用前的绑定（支持嵌套，原先未绑定则清除）。
     */
    public static <T> T callAs(long tenantId, Supplier<T> action) {
        Long previous = CURRENT.get();
        assertMaySwitch(previous, tenantId);
        CURRENT.set(tenantId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** 无返回值版本的 {@link #callAs}。 */
    public static void runAs(long tenantId, Runnable action) {
        callAs(tenantId, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 以系统模式执行跨租户操作。系统模式清除当前租户，使普通 Mapper 因缺少租户上下文而立即失败；
     * 只有 tenant.system 包中显式豁免的 Mapper 可以执行。进入动作会记录用途，不记录查询参数。
     */
    public static <T> T callAsSystem(String purpose, Supplier<T> action) {
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("系统模式用途不能为空");
        }
        Long previous = CURRENT.get();
        assertMaySwitch(previous, null);
        log.info("系统模式: {}", purpose);
        CURRENT.remove();
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** 无返回值版本的 {@link #callAsSystem(String, Supplier)}。 */
    public static void runAsSystem(String purpose, Runnable action) {
        callAsSystem(purpose, () -> {
            action.run();
            return null;
        });
    }

    /** 活跃事务已有租户绑定时，不允许切换到其他租户或系统模式。 */
    private static void assertMaySwitch(Long previous, Long target) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && previous != null && !previous.equals(target)) {
            throw new IllegalStateException("事务中不允许切换租户: " + previous + " -> "
                    + (target == null ? "system" : target));
        }
    }
}
