package com.seckill.seckill.metrics;

import com.seckill.seckill.cache.HotActivityLocalCache;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CacheMetricsConfig {
    @Bean
    MeterBinder activityCacheMetrics(HotActivityLocalCache cache) {
        // 手工创建的 Caffeine 不属于 Spring CacheManager，需要显式绑定才能导出统计量。
        return registry -> CaffeineCacheMetrics.monitor(registry, cache.asCache(), "hot-activity");
    }
}
