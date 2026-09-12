package com.seckill.goods.cache;

import com.seckill.common.redis.RedisKeys;
import com.seckill.goods.entity.Goods;
import com.seckill.goods.mapper.GoodsMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商品 ID 布隆过滤器（缓存穿透第一道防线）。
 *
 * 布隆过滤器的特性（面试必考）：
 * - 说"可能存在"有小概率误报（本项目误报率 1%）
 * - 说"一定不存在"则绝对准确（不存在误漏）
 * 所以用法是：布隆说"不存在"→ 直接拒绝，连缓存和 DB 都不碰；
 * 布隆说"可能存在"→ 继续走缓存/DB 正常流程，误报只是多查一次而已。
 *
 * M6 从 Guava 本地布隆换成 Redisson 分布式布隆，原因是多实例部署：
 * 本地布隆每个实例各存一份，A 实例创建的新商品只进了 A 的过滤器，
 * 请求被 Nacos 负载到 B 实例时会被误判成"一定不存在"而直接 404——
 * 这是把"绝不误漏"的特性用反了方向的经典事故。
 * 换成 Redis bitmap 实现后所有实例共享同一份，新建商品对全集群立即可见。
 *
 * 代价：每次判断多一次 Redis 往返。但穿透防线本来就是为了挡住"打向 DB 的无效请求"，
 * 一次 Redis GET 级别的开销远小于一次 DB 查询，且热点商品还有本地缓存兜在前面。
 */
@Slf4j
@Component
public class GoodsBloomFilter {

    private final GoodsMapper goodsMapper;
    private final RBloomFilter<String> filter;
    private final long expectedInsertions;
    private final double falseProbability;

    public GoodsBloomFilter(GoodsMapper goodsMapper,
                            RedissonClient redisson,
                            @Value("${seckill.bloom.expected-insertions:100000}") long expectedInsertions,
                            @Value("${seckill.bloom.false-probability:0.01}") double falseProbability) {
        this.goodsMapper = goodsMapper;
        this.expectedInsertions = expectedInsertions;
        this.falseProbability = falseProbability;
        this.filter = redisson.getBloomFilter(RedisKeys.goodsBloom());
    }

    /**
     * 启动时初始化。tryInit 是幂等的：只有"这个布隆还不存在"时才返回 true 并写入容量/误报率参数，
     * 已存在时返回 false 且保留原参数——所以只有第一个启动的实例（或 Redis 被清空后的实例）
     * 需要把全量商品 ID 灌一遍，后续实例重启直接复用，不用每次都做 O(N) 次 Redis 写入。
     * 布隆的 add 本身也是幂等的，重复灌入只会浪费往返、不会改变判定结果。
     */
    @PostConstruct
    public void load() {
        if (!filter.tryInit(expectedInsertions, falseProbability)) {
            log.info("分布式布隆已存在，跳过全量灌入: key={}, 容量={}, 误报率={}",
                    RedisKeys.goodsBloom(), expectedInsertions, falseProbability);
            return;
        }
        List<Goods> all = goodsMapper.selectList(null);
        all.forEach(goods -> filter.add(String.valueOf(goods.getId())));
        log.info("分布式布隆初始化完成: key={}, 商品数={}", RedisKeys.goodsBloom(), all.size());
    }

    /** 判断商品 ID 是否可能存在（false 即绝对不存在，可直接拒绝请求） */
    public boolean mightContain(Long goodsId) {
        return filter.contains(String.valueOf(goodsId));
    }

    /** 商品创建时同步加入：分布式布隆让新商品对全集群实例立即可见 */
    public void add(Long goodsId) {
        filter.add(String.valueOf(goodsId));
    }
}
