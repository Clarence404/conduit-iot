package dev.conduit.server.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TenantContext 单元测试：系统模式恢复、审计日志与一事务一租户断言。 */
@ExtendWith(OutputCaptureExtension.class)
class TenantContextTest {

    @AfterEach
    void clean() {
        TenantContext.clear();
        TransactionSynchronizationManager.clear();
    }

    @Test
    void systemModeClearsAndRestoresNestedBindings(CapturedOutput output) {
        TenantContext.set(7L);

        long nested = TenantContext.callAsSystem("platform-stats", () -> {
            assertThat(TenantContext.get()).isNull();
            return TenantContext.callAs(9L, TenantContext::require);
        });

        assertThat(nested).isEqualTo(9L);
        assertThat(TenantContext.require()).isEqualTo(7L);
        assertThat(output).contains("系统模式: platform-stats").doesNotContain("query-parameter");
    }

    @Test
    void activeTransactionCannotSwitchTenantOrEnterSystemMode() {
        TenantContext.set(7L);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> TenantContext.callAs(8L, () -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("事务中不允许切换租户: 7 -> 8");
        assertThatThrownBy(() -> TenantContext.callAsSystem("platform-stats", () -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("事务中不允许切换租户: 7 -> system");
    }

    @Test
    void unboundTransactionAndSwitchWithoutTransactionAreAllowed() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThat(TenantContext.callAs(7L, TenantContext::require)).isEqualTo(7L);
        assertThat(TenantContext.callAsSystem("platform-stats", () -> TenantContext.get())).isNull();

        TransactionSynchronizationManager.setActualTransactionActive(false);
        TenantContext.set(7L);
        assertThat(TenantContext.callAs(8L, TenantContext::require)).isEqualTo(8L);
        assertThat(TenantContext.require()).isEqualTo(7L);
    }
}
