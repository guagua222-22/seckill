package com.seckill.seckill.metrics;

import com.seckill.common.redis.RedisKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * M7：白名单活动库存的异步快照。抓取线程只读内存，不在 scrape 或下单热路径读 Redis。
 * 最多 32 个活动、每轮一次 MGET；不使用 KEYS/全库扫描。失败值为 NaN，不能伪装成售罄。
 */
@Component
public class StockMetrics {
    private final StringRedisTemplate redis;
    private final List<Long> activityIds;
    private final Counter errors;
    private volatile Map<Long, Double> snapshot = Map.of();
    private volatile double lastSuccess = Double.NaN;
    private volatile double available;
    @Value("${seckill.monitoring.stock-refresh-ms:15000}")
    private long refreshMillis = 15000;
    private ScheduledExecutorService scheduler;

    public StockMetrics(StringRedisTemplate redis, MeterRegistry registry,
                        @Value("${seckill.monitoring.activity-ids:}") String configuredIds) {
        this.redis = redis;
        this.activityIds = Arrays.stream(configuredIds.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(Long::valueOf).distinct().toList();
        if (activityIds.size() > 32 || activityIds.stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException("monitoring.activity-ids requires at most 32 positive IDs");
        }
        errors = registry.counter("seckill.stock.collection.errors");
        Gauge.builder("seckill.stock.monitored.activities", activityIds, List::size).register(registry);
        Gauge.builder("seckill.stock.collection.up", this, m -> m.available).register(registry);
        Gauge.builder("seckill.stock.collection.last.success.timestamp", this, m -> m.lastSuccess)
                .baseUnit("seconds").register(registry);
        for (Long id : activityIds) {
            Gauge.builder("seckill.stock.remaining", this, m -> m.snapshot.getOrDefault(id, Double.NaN))
                    .description("Redis admission stock; -1 means key not preheated, NaN means unknown")
                    .tag("activity_id", id.toString()).register(registry);
        }
    }

    @PostConstruct
    void start() {
        if (refreshMillis < 1000) {
            throw new IllegalArgumentException("stock-refresh-ms must be at least 1000ms");
        }
        if (activityIds.isEmpty()) return;
        // 不占用 @Scheduled 的业务线程：Redis 读超时不能延误消息补偿、流水对账任务。
        // 单线程 + fixedDelay 保证上一轮完成后才等下一轮，采集慢时不会堆积任务。
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("stock-metrics").daemon(true).factory());
        scheduler.scheduleWithFixedDelay(this::refresh, 0, refreshMillis, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        // Spring 容器退出时关闭自己的线程，避免热重启后遗留后台任务。
        if (scheduler != null) scheduler.shutdownNow();
    }

    /** 后台只更新完整快照；所有 Gauge 回调都只读内存。 */
    public void refresh() {
        if (activityIds.isEmpty()) return;
        try {
            List<String> values = redis.opsForValue().multiGet(activityIds.stream().map(RedisKeys::stock).toList());
            if (values == null || values.size() != activityIds.size()) {
                throw new IllegalStateException("Incomplete Redis stock snapshot");
            }
            Map<Long, Double> next = new LinkedHashMap<>();
            for (int i = 0; i < activityIds.size(); i++) {
                next.put(activityIds.get(i), values.get(i) == null ? -1D : (double) Long.parseLong(values.get(i)));
            }
            snapshot = Map.copyOf(next);
            lastSuccess = System.currentTimeMillis() / 1000D;
            available = 1;
        } catch (RuntimeException e) {
            snapshot = Map.of();
            available = 0;
            errors.increment();
        }
    }
}
