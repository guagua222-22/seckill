package com.seckill.goods.config;

import com.seckill.goods.cache.HotGoodsLocalCache;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CacheMetricsConfig {
    @Bean
    MeterBinder goodsCacheMetrics(HotGoodsLocalCache cache) {
        // 固定缓存名控制标签数量；累计 hit/miss 交给 Prometheus 计算时间窗口命中率。
        return registry -> CaffeineCacheMetrics.monitor(registry, cache.asCache(), "hot-goods");
    }
}
