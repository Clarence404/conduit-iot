package dev.conduit.server.pipeline;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 流水线并发消费：workers 条线程各用一个独立的消费者名，消费组才能在它们之间分摊消息；
 * 若名字重复，多条线程会以同一消费者身份读取，失去分摊的意义。
 */
class PipelineWorkerTest {

    private static PipelineProperties props(int workers) {
        return new PipelineProperties(true, "g", "srv", 100, Duration.ofMillis(10), Duration.ofSeconds(60),
                Duration.ofSeconds(10), 5, workers);
    }

    @Test
    void 每个worker使用独立的消费者名() throws Exception {
        PipelineRunner runner = mock(PipelineRunner.class);
        Set<String> consumers = ConcurrentHashMap.newKeySet();
        CountDownLatch seen = new CountDownLatch(3);
        when(runner.pollOnce(anyString())).thenAnswer(inv -> {
            if (consumers.add(inv.getArgument(0))) {
                seen.countDown();
            }
            Thread.sleep(5);
            return 0;
        });
        when(runner.claimOnce(anyString())).thenReturn(0);

        PipelineWorker worker = new PipelineWorker(runner, props(3), Clock.systemUTC());
        worker.start();
        try {
            assertThat(seen.await(5, TimeUnit.SECONDS)).as("三个消费者都应开始读取").isTrue();
        } finally {
            worker.stop();
        }

        assertThat(consumers).containsExactlyInAnyOrder("srv-0", "srv-1", "srv-2");
        assertThat(worker.isRunning()).isFalse();
    }

    @Test
    void workers非正数时启动即失败而不是静默不消费() {
        assertThatThrownBy(() -> props(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workers");
    }
}
