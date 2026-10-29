package dev.conduit.server.metrics;

import dev.conduit.server.metrics.ThroughputSampler.ThroughputView;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 吞吐采样器：速率由两次采样的差值算出，采样不足时必须是 null 而不是 0。 */
class ThroughputSamplerTest {

    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final ThroughputSampler sampler = new ThroughputSampler(meters, now::get);

    /** 推进时钟并采样一次。 */
    private void tick(long millis) {
        now.addAndGet(millis);
        sampler.sample();
    }

    private void ingest(int n) {
        meters.counter("conduit.pipeline.processed", "type", "property").increment(n);
    }

    @Test
    void 没有采样时速率未知而不是零() {
        ThroughputView v = sampler.snapshot();
        assertThat(v.ingestTps()).isNull();
        assertThat(v.peakTps()).isNull();
        assertThat(v.ingestTotal()).isZero();
    }

    @Test
    void 只采样一次仍然未知() {
        sampler.sample();
        assertThat(sampler.snapshot().ingestTps()).isNull();
    }

    @Test
    void 两次采样之间的增量除以耗时得到速率() {
        sampler.sample();
        ingest(500);
        tick(5_000);
        ThroughputView v = sampler.snapshot();
        assertThat(v.ingestTps()).isEqualTo(100.0);
        assertThat(v.ingestTotal()).isEqualTo(500);
    }

    @Test
    void 真实的零流量速率是零而不是未知() {
        sampler.sample();
        tick(5_000);
        assertThat(sampler.snapshot().ingestTps()).isEqualTo(0.0);
    }

    @Test
    void 峰值保留历史最高值() {
        sampler.sample();
        ingest(1_000);
        tick(5_000);
        assertThat(sampler.snapshot().ingestTps()).isEqualTo(200.0);
        tick(5_000);
        ThroughputView v = sampler.snapshot();
        assertThat(v.ingestTps()).isEqualTo(100.0);
        assertThat(v.peakTps()).isEqualTo(200.0);
    }

    @Test
    void 缓冲填满后只统计最近窗口() {
        // 填满 13 次采样（窗口 60 秒），期间每次 +100 条
        for (int i = 0; i < ThroughputSampler.SAMPLES; i++) {
            ingest(100);
            tick(5_000);
        }
        assertThat(sampler.snapshot().ingestTps()).isEqualTo(20.0);
        // 此后完全没有流量，窗口滑过 60 秒后速率应回落到 0
        for (int i = 0; i < ThroughputSampler.SAMPLES; i++) {
            tick(5_000);
        }
        assertThat(sampler.snapshot().ingestTps()).isEqualTo(0.0);
        assertThat(sampler.snapshot().ingestTotal()).isEqualTo(100L * ThroughputSampler.SAMPLES);
    }

    @Test
    void 死信按所有原因合计() {
        meters.counter("conduit.pipeline.dead", "reason", "PROCESSING_ERROR").increment(3);
        meters.counter("conduit.pipeline.dead", "reason", "TENANT_DISABLED").increment(2);
        sampler.sample();
        assertThat(sampler.snapshot().deadTotal()).isEqualTo(5);
    }
}
