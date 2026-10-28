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
}
