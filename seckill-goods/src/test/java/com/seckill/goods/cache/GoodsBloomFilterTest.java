package com.seckill.goods.cache;

import com.seckill.common.redis.RedisKeys;
import com.seckill.goods.entity.Goods;
import com.seckill.goods.mapper.GoodsMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分布式布隆过滤器单测（M6-4）。
 *
 * 重点守住两条多实例语义：
 * 1. tryInit 幂等——只有"布隆还不存在"时才做全量灌入，后续实例重启直接复用，
 *    否则每次重启都是 O(N) 次 Redis 写入，实例越多启动越慢；
 * 2. 判定与写入都落到 Redis 的同一份 bitmap 上，不再有"这台实例拦、那台实例放"的不一致。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoodsBloomFilterTest {

    @Mock
    private GoodsMapper goodsMapper;
    @Mock
    private RedissonClient redisson;
    @Mock
    private RBloomFilter<String> filter;

    private GoodsBloomFilter bloomFilter;

    @BeforeEach
    void setUp() {
        when(redisson.<String>getBloomFilter(RedisKeys.goodsBloom())).thenReturn(filter);
        bloomFilter = new GoodsBloomFilter(goodsMapper, redisson, 100_000, 0.01);
    }

    private Goods goods(long id) {
        Goods g = new Goods();
        g.setId(id);
        return g;
    }

    @Test
    @DisplayName("首次初始化：按配置的容量/误报率建布隆，并把库里已有商品全量灌入")
    void firstInitLoadsAllGoodsIds() {
        when(filter.tryInit(100_000, 0.01)).thenReturn(true);
        when(goodsMapper.selectList(null)).thenReturn(List.of(goods(1L), goods(2L)));

        bloomFilter.load();

        verify(filter).tryInit(100_000, 0.01);
        verify(filter).add("1");
        verify(filter).add("2");
    }

    @Test
    @DisplayName("布隆已存在：跳过全量灌入，不查 DB 也不重复写 Redis")
    void existingBloomSkipsFullReload() {
        when(filter.tryInit(100_000, 0.01)).thenReturn(false);

        bloomFilter.load();

        // 多实例/重启场景的关键：不做 O(N) 次 Redis 写入
        verify(goodsMapper, never()).selectList(any());
        verify(filter, never()).add(anyString());
    }

    @Test
    @DisplayName("穿透判定：布隆说不存在就一定不存在，直接拒绝")
    void mightContainDelegatesToSharedFilter() {
        when(filter.contains("9")).thenReturn(false);
        when(filter.contains("1")).thenReturn(true);

        assertFalse(bloomFilter.mightContain(9L));
        assertTrue(bloomFilter.mightContain(1L));
    }

    @Test
    @DisplayName("新建商品：同步写进共享布隆，全集群实例立即可见")
    void addWritesToSharedFilter() {
        bloomFilter.add(7L);

        verify(filter).add("7");
    }
}
