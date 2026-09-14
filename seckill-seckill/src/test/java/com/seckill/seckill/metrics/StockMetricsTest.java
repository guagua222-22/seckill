package com.seckill.seckill.metrics;

import com.seckill.common.redis.RedisKeys;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;
import java.time.Duration;

/** 验证监控故障隔离：失败不能显示为 0 库存，Prometheus 抓取不能额外访问 Redis。 */
class StockMetricsTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    @Test
    void lifecycleStartsIndependentBackgroundCollection() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList())).thenReturn(List.of("6"));
        StockMetrics metrics = new StockMetrics(redis, registry, "101");
        try {
            metrics.start();
            // 无需 HTTP 请求触发，后台线程启动后就应发布首个库存快照。
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertEquals(6, stock("101")));
        } finally {
            metrics.stop();
        }
    }

    @Test
    void snapshotAndScrapeHaveNoRedisSideEffects() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(List.of(RedisKeys.stock(101L), RedisKeys.stock(102L))))
                .thenReturn(Arrays.asList("80", null));
        StockMetrics metrics = new StockMetrics(redis, registry, "101,102,101");
        assertTrue(Double.isNaN(stock("101")));
        metrics.refresh();
        assertEquals(80, stock("101"));
        assertEquals(-1, stock("102"));
        assertEquals(1, gauge("seckill.stock.collection.up"));
        clearInvocations(redis, values);
        String scrape = registry.scrape();
        assertTrue(scrape.contains("seckill_stock_remaining{activity_id=\"101\",} 80.0"));
        registry.scrape();
        verifyNoInteractions(redis, values);
    }

    @Test
    void failureInvalidatesSnapshotAndRecoveryRestoresIt() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList())).thenReturn(List.of("10"))
                .thenThrow(new IllegalStateException("Redis unavailable")).thenReturn(List.of("9"));
        StockMetrics metrics = new StockMetrics(redis, registry, "101");
        metrics.refresh();
        double lastSuccess = gauge("seckill.stock.collection.last.success.timestamp");
        metrics.refresh();
        assertTrue(Double.isNaN(stock("101")));
        assertEquals(0, gauge("seckill.stock.collection.up"));
        assertEquals(lastSuccess, gauge("seckill.stock.collection.last.success.timestamp"));
        assertEquals(1, registry.get("seckill.stock.collection.errors").counter().count());
        metrics.refresh();
        assertEquals(9, stock("101"));
        assertEquals(1, gauge("seckill.stock.collection.up"));
    }

    @Test
    void disabledCollectionDoesNotTouchRedis() {
        new StockMetrics(redis, registry, "").refresh();
        verifyNoInteractions(redis);
        assertEquals(0, gauge("seckill.stock.monitored.activities"));
        assertTrue(Double.isNaN(gauge("seckill.stock.collection.last.success.timestamp")));
    }

    @Test
    void invalidOrUnboundedLabelsFailAtStartup() {
        String tooMany = LongStream.rangeClosed(1, 33).mapToObj(Long::toString).collect(Collectors.joining(","));
        assertThrows(IllegalArgumentException.class, () -> new StockMetrics(redis, registry, tooMany));
        assertThrows(IllegalArgumentException.class, () -> new StockMetrics(redis, registry, "0"));
        assertThrows(IllegalArgumentException.class, () -> new StockMetrics(redis, registry, "oops"));
    }

    @Test
    void corruptValueDoesNotPublishPartialSnapshot() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList())).thenReturn(List.of("12", "bad"));
        StockMetrics metrics = new StockMetrics(redis, registry, "101,102");
        metrics.refresh();
        assertTrue(Double.isNaN(stock("101")));
        assertTrue(Double.isNaN(stock("102")));
        assertEquals(1, registry.get("seckill.stock.collection.errors").counter().count());
    }

    private double stock(String id) {
        return registry.get("seckill.stock.remaining").tag("activity_id", id).gauge().value();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }
}
