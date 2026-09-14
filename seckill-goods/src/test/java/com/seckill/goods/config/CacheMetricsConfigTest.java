package com.seckill.goods.config;

import com.seckill.goods.cache.HotGoodsLocalCache;
import com.seckill.goods.vo.CachedGoodsVO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 防止手工创建 Caffeine 忘记 recordStats，导致注册了指标却永远是零。 */
class CacheMetricsConfigTest {
    @Test
    void goodsCacheExportsRealHitsAndMisses() {
        var cache = new HotGoodsLocalCache(60, 10);
        var registry = new SimpleMeterRegistry();
        new CacheMetricsConfig().goodsCacheMetrics(cache).bindTo(registry);
        var goods = new CachedGoodsVO();
        cache.getBase(1L, id -> goods);
        assertSame(goods, cache.getBase(1L, id -> { throw new AssertionError("不应重复回源"); }));
        assertEquals(1, registry.get("cache.gets").tag("result", "hit").functionCounter().count());
        assertEquals(1, registry.get("cache.gets").tag("result", "miss").functionCounter().count());
    }
}
