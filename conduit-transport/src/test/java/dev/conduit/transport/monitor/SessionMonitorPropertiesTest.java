package dev.conduit.transport.monitor;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SessionMonitorProperties 校验：stallSamples 小于 2 会误报，必须拒绝。 */
class SessionMonitorPropertiesTest {

    private static SessionMonitorProperties with(int stallSamples) {
        return new SessionMonitorProperties(true, "http://x", "k", "s",
                Duration.ofSeconds(30), stallSamples, DataSize.ofMegabytes(256));
    }

    @Test
    void rejectsStallSamplesBelowTwo() {
        assertThatThrownBy(() -> with(1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsTwoOrMore() {
        assertThatCode(() -> with(2)).doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankCredentialsWhenEnabled() {
        assertThatThrownBy(() -> new SessionMonitorProperties(true, "http://x", "", "s",
                Duration.ofSeconds(30), 6, DataSize.ofMegabytes(256))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionMonitorProperties(true, "http://x", "k", null,
                Duration.ofSeconds(30), 6, DataSize.ofMegabytes(256))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsBlankCredentialsWhenDisabled() {
        assertThatCode(() -> new SessionMonitorProperties(false, "http://x", null, null,
                Duration.ofSeconds(30), 6, DataSize.ofMegabytes(256))).doesNotThrowAnyException();
    }

    @Test
    void toStringMasksSecrets() {
        String text = new SessionMonitorProperties(true, "http://x", "my-key", "my-secret",
                Duration.ofSeconds(30), 6, DataSize.ofMegabytes(256)).toString();
        org.assertj.core.api.Assertions.assertThat(text).doesNotContain("my-key").doesNotContain("my-secret");
    }

    @Test
    void newAlarmSettingsDefaultFromTheShortConstructor() {
        SessionMonitorProperties p = with(6);
        org.assertj.core.api.Assertions.assertThat(p.mqueueWarnLen()).isEqualTo(50_000);
        org.assertj.core.api.Assertions.assertThat(p.nodeMemoryWarnRatio()).isEqualTo(0.8);
    }

    @Test
    void rejectsInvalidAlarmSettings() {
        assertThatThrownBy(() -> new SessionMonitorProperties(true, "http://x", "k", "s",
                Duration.ofSeconds(30), 6, DataSize.ofMegabytes(256), -1, 0.8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionMonitorProperties(true, "http://x", "k", "s",
                Duration.ofSeconds(30), 6, DataSize.ofMegabytes(256), 50_000, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SessionMonitorProperties(true, "http://x", "k", "s",
                Duration.ofSeconds(30), 6, DataSize.ofMegabytes(256), 50_000, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 按 Spring 的真实绑定路径构造：record 有多个构造器时必须标明绑定用哪一个，否则启动失败（2026-11-12 部署时实际发生）。 */
    @Test
    void bindsThroughSpringWithDefaultsForTheNewAlarmSettings() {
        var source = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(java.util.Map.of(
                "conduit.transport.monitor.api-key", "k", "conduit.transport.monitor.api-secret", "s"));
        SessionMonitorProperties p = new org.springframework.boot.context.properties.bind.Binder(source)
                .bindOrCreate("conduit.transport.monitor", SessionMonitorProperties.class);
        org.assertj.core.api.Assertions.assertThat(p.mqueueWarnLen()).isEqualTo(50_000);
        org.assertj.core.api.Assertions.assertThat(p.nodeMemoryWarnRatio()).isEqualTo(0.8);
        org.assertj.core.api.Assertions.assertThat(p.heapLimit()).isEqualTo(DataSize.ofMegabytes(256));
    }
}
