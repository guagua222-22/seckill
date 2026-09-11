package com.seckill.goods.cache;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.seckill.goods.entity.Goods;
import com.seckill.goods.mapper.GoodsMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
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
 * 单体阶段用 Guava 本地布隆（内存版）；M5 拆微服务后应换 Redisson 分布式布隆，
 * 否则多实例各自维护一份会不一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoodsBloomFilter {

    private final GoodsMapper goodsMapper;

    /** 预期 10 万商品、误报率 1% */
    private final BloomFilter<String> filter =
            BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), 100_000, 0.01);

    /** 启动时把已有商品 ID 全部灌入，保证过滤器初始状态完整 */
    @PostConstruct
    public void load() {
        List<Goods> all = goodsMapper.selectList(null);
        all.forEach(goods -> filter.put(String.valueOf(goods.getId())));
        log.info("布隆过滤器加载完成，商品数: {}", all.size());
    }

    /** 判断商品 ID 是否可能存在 */
    public boolean mightContain(Long goodsId) {
        return filter.mightContain(String.valueOf(goodsId));
    }

    /** 商品创建时同步加入，保持过滤器与数据一致 */
    public void add(Long goodsId) {
        filter.put(String.valueOf(goodsId));
    }
}
