package com.seckill.goods.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.seckill.goods.vo.CachedGoodsVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * 热点商品本地缓存（M6 热点隔离）。
 *
 * 要解决的问题：热点活动开抢瞬间，所有实例的请求都打向 Redis 的同一个
 * seckill:goods:{id} key——Redis 单线程模型下这个 key 就是全局瓶颈（热点 key）。
 * 解法是"多级缓存"：在 JVM 内再加一层 Caffeine，把打到 Redis 的 QPS 除以实例内并发度。
 *
 * 三个关键设计：
 * 1. 只对热点商品启用（isHot=1 的活动关联商品）。普通商品走 Redis 一层就够，
 *    多一层本地缓存只会增加不一致窗口，没有收益；
 * 2. TTL 取秒级（默认 1s）。本地缓存必然带来"各实例数据短暂不一致"，
 *    商品详情是读多写少的展示数据，1s 最终一致可接受；
 * 3. 缓存值只放商品基础信息（CachedGoodsVO），绝不放含库存的详情 VO——
 *    库存必须每次现查 DB/Redis，否则热点商品会在 TTL 窗口内看到过期库存，
 *    这是"展示数据可短暂不一致、交易数据必须强一致"的分界线。
 *
 * Caffeine.get(key, loader) 自带"同 key 只放一个线程回源"的语义，
 * 本地层也顺带挡了击穿（与 Redis 层的逻辑过期互斥重建是同一思想的两级落地）。
 */
@Component
public class HotGoodsLocalCache {

    private final Cache<Long, CachedGoodsVO> local;

    /** 热点商品 ID 集合：由预热任务周期性刷新，volatile 保证刷新对读线程立即可见 */
    private volatile Set<Long> hotGoodsIds = Set.of();

    public HotGoodsLocalCache(@Value("${seckill.hot-cache.ttl-seconds:1}") long ttlSeconds,
                              @Value("${seckill.hot-cache.max-size:1024}") long maxSize) {
        this.local = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .build();
    }

    public boolean isHot(Long goodsId) {
        return hotGoodsIds.contains(goodsId);
    }

    /** 预热任务整批刷新：直接替换引用，读线程要么看到旧集合要么看到新集合，不会看到半更新状态 */
    public void refreshHotIds(Set<Long> ids) {
        this.hotGoodsIds = Set.copyOf(ids);
    }

    /** 活动创建时立即标记：不用等下一轮预热任务（最多 30s）才生效。
     *  低频管理路径，直接 copy-on-write 换整个集合，读线程永远看到一致的快照 */
    public void markHot(Long goodsId) {
        Set<Long> merged = new HashSet<>(hotGoodsIds);
        merged.add(goodsId);
        this.hotGoodsIds = Set.copyOf(merged);
    }

    /** 热点商品读本地缓存；loader 内是原有的"布隆→Redis→DB"链路。loader 抛异常时不写缓存 */
    public CachedGoodsVO getBase(Long goodsId, Function<Long, CachedGoodsVO> loader) {
        return local.get(goodsId, loader);
    }

    /** 商品改名/下架时失效：本地缓存最多脏 TTL 秒，写操作主动清掉把窗口缩到 0 */
    public void invalidate(Long goodsId) {
        local.invalidate(goodsId);
    }
}
