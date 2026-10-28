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
}
