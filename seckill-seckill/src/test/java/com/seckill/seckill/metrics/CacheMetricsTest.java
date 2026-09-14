package com.seckill.seckill.metrics;

import com.seckill.common.api.goods.ActivityInfoDTO;
import com.seckill.seckill.cache.HotActivityLocalCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CacheMetricsTest {
    @Test
    void manuallyCreatedCaffeineIsBoundAndCountsHitsAndMisses() {
        HotActivityLocalCache cache = new HotActivityLocalCache(60000, 10);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new CacheMetricsConfig().activityCacheMetrics(cache).bindTo(registry);
        ActivityInfoDTO activity = new ActivityInfoDTO();
        cache.get(1L, id -> activity);
        assertSame(activity, cache.get(1L, id -> { throw new AssertionError("不应再次回源"); }));
        assertEquals(1, registry.get("cache.gets").tag("result", "miss").functionCounter().count());
        assertEquals(1, registry.get("cache.gets").tag("result", "hit").functionCounter().count());
    }
}
