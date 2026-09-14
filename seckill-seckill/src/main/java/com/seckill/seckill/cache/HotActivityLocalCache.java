package com.seckill.seckill.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.seckill.common.api.goods.ActivityInfoDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Function;

/**
 * 活动信息本地缓存（M6 热点隔离）。
 *
 * 要解决的问题：下单热路径每一单都要读 Redis 的 seckill:activity:{id} 再做一次
 * Jackson 反序列化。开抢时全场就一两个活动在卖，这个 key 是整个系统最热的 key，
 * 而且 Redis 单线程，QPS 上限被它一个 key 卡住。
 *
 * 解法是在 JVM 内再加一层 Caffeine：同一实例内 N 个并发请求只打一次 Redis、
 * 只反序列化一次，Redis 热点 key 的压力直接除以实例内并发度。
 *
 * 与 goods 侧 HotGoodsLocalCache 的差别：这里不按 isHot 筛，因为"正在被下单的活动"
 * 天然就是热点，无需运营标记；条目数由 max-size + 秒级 TTL 双重兜住，不会无限膨胀。
 *
 * 为什么敢缓存：缓存的是活动配置（名字/时间窗/价格/商品），不是库存。
 * 库存永远走 Lua 现扣，正确性不依赖这层缓存；代价只是活动配置改动最多延迟
 * ttl 毫秒生效——而活动进行中改价改时间本身就是运营禁忌，这个窗口可以接受。
 *
 * Caffeine.get(key, loader) 保证同 key 只有一个线程回源，loader 抛异常时不写缓存，
 * 所以 goods-service 挂掉不会把失败结果缓存住（不会"缓存污染"）。
 */
@Component
public class HotActivityLocalCache {

    private final Cache<Long, ActivityInfoDTO> local;

    public HotActivityLocalCache(@Value("${seckill.hot-cache.activity-ttl-ms:1000}") long ttlMillis,
                                 @Value("${seckill.hot-cache.activity-max-size:256}") long maxSize) {
        this.local = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofMillis(ttlMillis))
                .recordStats()
                .build();
    }

    /**
     * 读活动信息；loader 内是原有的"Redis 预热缓存 → Feign 兜底"链路。
     * 返回的 DTO 是共享快照，调用方只读不改（改一个字段会污染同实例内所有并发请求）。
     */
    public ActivityInfoDTO get(Long activityId, Function<Long, ActivityInfoDTO> loader) {
        return local.get(activityId, loader);
    }

    /** 活动配置变更时主动失效，把脏读窗口从 ttl 压到 0（当前由 goods 侧改动触发，预留接口） */
    public void invalidate(Long activityId) {
        local.invalidate(activityId);
    }

    /** M6 实验台用：当前条目数 + 命中率统计 */
    public CacheStats stats() {
        return local.stats();
    }

    /** M6 实验台用：缓存实例引用（面板轮询 estimatedSize 不需要 stats 开销） */
    public Cache<Long, ActivityInfoDTO> asCache() {
        return local;
    }
}
