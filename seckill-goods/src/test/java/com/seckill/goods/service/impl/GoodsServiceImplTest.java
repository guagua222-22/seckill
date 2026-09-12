package com.seckill.goods.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.goods.cache.GoodsBloomFilter;
import com.seckill.goods.dto.GoodsDTO;
import com.seckill.goods.entity.Goods;
import com.seckill.goods.entity.SeckillActivity;
import com.seckill.goods.entity.Stock;
import com.seckill.goods.mapper.GoodsMapper;
import com.seckill.goods.mapper.SeckillActivityMapper;
import com.seckill.goods.mapper.StockMapper;
import com.seckill.goods.vo.CachedGoodsVO;
import com.seckill.goods.vo.GoodsDetailVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品详情缓存单测：覆盖三件套的各个分支。
 * 布隆 miss（穿透第一层）→ 缓存命中 → 空值缓存 → 逻辑过期抢锁重建 → 未命中回填。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoodsServiceImplTest {

    /**
     * 纯 Mockito 环境没有 Spring 启动流程，MyBatis-Plus 的 lambda 列缓存不会初始化，
     * LambdaUpdateWrapper.set(实体::字段) 会抛"can not find lambda cache"。
     * update() 现在要同步刷新 t_stock / t_seckill_activity 的 goods_name 冗余列，
     * 所以这两个实体的 TableInfo 都得手动建好。
     */
    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Stock.class);
        TableInfoHelper.initTableInfo(assistant, SeckillActivity.class);
    }

    @Mock
    private GoodsMapper goodsMapper;
    @Mock
    private StockMapper stockMapper;
    @Mock
    private SeckillActivityMapper activityMapper;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private GoodsBloomFilter bloomFilter;

    /** 真实 ObjectMapper（注册时间模块），手动注入被测类，避免 Mockito 代理干扰序列化 */
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private GoodsServiceImpl goodsService;

    private Goods goods;
    private CachedGoodsVO cached;

    @BeforeEach
    void setUp() {
        // 手动构造被测对象：Redis 等外部依赖用 mock，ObjectMapper 用真实实例
        goodsService = new GoodsServiceImpl(goodsMapper, stockMapper, activityMapper,
                redis, objectMapper, bloomFilter);

        goods = new Goods();
        goods.setId(1L);
        goods.setGoodsName("iPhone 16 Pro");
        goods.setNormalPrice(new BigDecimal("7999.00"));
        goods.setStatus(1);
        goods.setCreateTime(LocalDateTime.now());

        cached = new CachedGoodsVO();
        cached.setId(1L);
        cached.setGoodsName("iPhone 16 Pro");
        cached.setNormalPrice(new BigDecimal("7999.00"));
        cached.setStatus(1);
        cached.setExpireAt(LocalDateTime.now().plusMinutes(10));

        when(redis.opsForValue()).thenReturn(valueOps);
    }

    private String cachedJson() throws Exception {
        return objectMapper.writeValueAsString(cached);
    }

    @Test
    @DisplayName("穿透第一层：布隆说一定不存在，直接拒（不碰缓存和 DB）")
    void bloomMiss() {
        when(bloomFilter.mightContain(99L)).thenReturn(false);
        BizException e = assertThrows(BizException.class, () -> goodsService.getDetail(99L));
        assertEquals(ErrorCode.GOODS_NOT_FOUND.getCode(), e.getCode());
        verify(valueOps, never()).get(anyString());
        verify(goodsMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("缓存命中且未逻辑过期：直接返回，不查 DB")
    void cacheHit() throws Exception {
        when(bloomFilter.mightContain(1L)).thenReturn(true);
        when(valueOps.get(anyString())).thenReturn(cachedJson());
        when(stockMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        GoodsDetailVO vo = goodsService.getDetail(1L);
        assertEquals("iPhone 16 Pro", vo.getGoodsName());
        verify(goodsMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("穿透第二层：空值缓存直接拒绝")
    void emptyCache() {
        when(bloomFilter.mightContain(1L)).thenReturn(true);
        when(valueOps.get(anyString())).thenReturn("NULL");
        BizException e = assertThrows(BizException.class, () -> goodsService.getDetail(1L));
        assertEquals(ErrorCode.GOODS_NOT_FOUND.getCode(), e.getCode());
        verify(goodsMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("击穿防护：逻辑过期且抢到重建锁 → 重建缓存并返回旧值")
    void logicalExpireRebuild() throws Exception {
        cached.setExpireAt(LocalDateTime.now().minusMinutes(1)); // 已逻辑过期
        when(bloomFilter.mightContain(1L)).thenReturn(true);
        when(valueOps.get(anyString())).thenReturn(cachedJson());
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(goodsMapper.selectById(1L)).thenReturn(goods);
        when(stockMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        GoodsDetailVO vo = goodsService.getDetail(1L);
        assertEquals("iPhone 16 Pro", vo.getGoodsName());
        verify(goodsMapper, times(1)).selectById(1L); // 只有抢到锁的请求查了 DB
        verify(valueOps).set(anyString(), anyString(), any(Duration.class)); // 重建写入
    }

    @Test
    @DisplayName("击穿防护：逻辑过期但没抢到重建锁 → 返回旧值，不查 DB")
    void logicalExpireNoLock() throws Exception {
        cached.setExpireAt(LocalDateTime.now().minusMinutes(1));
        when(bloomFilter.mightContain(1L)).thenReturn(true);
        when(valueOps.get(anyString())).thenReturn(cachedJson());
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);
        when(stockMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        GoodsDetailVO vo = goodsService.getDetail(1L);
        assertEquals("iPhone 16 Pro", vo.getGoodsName());
        verify(goodsMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("缓存未命中：查 DB 回填缓存")
    void cacheMiss() {
        when(bloomFilter.mightContain(1L)).thenReturn(true);
        when(valueOps.get(anyString())).thenReturn(null);
        when(goodsMapper.selectById(1L)).thenReturn(goods);
        when(stockMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        GoodsDetailVO vo = goodsService.getDetail(1L);
        assertEquals("iPhone 16 Pro", vo.getGoodsName());
        verify(valueOps).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("缓存未命中且 DB 没有：写空值缓存并拒绝")
    void cacheMissDbEmpty() {
        when(bloomFilter.mightContain(1L)).thenReturn(true);
        when(valueOps.get(anyString())).thenReturn(null);
        when(goodsMapper.selectById(1L)).thenReturn(null);
        BizException e = assertThrows(BizException.class, () -> goodsService.getDetail(1L));
        assertEquals(ErrorCode.GOODS_NOT_FOUND.getCode(), e.getCode());
        verify(valueOps).set(anyString(), eq("NULL"), any(Duration.class));
    }

    @Test
    @DisplayName("创建商品：初始化 0 库存行并把 ID 加入布隆")
    void createInitializesStockAndBloom() {
        GoodsDTO dto = new GoodsDTO();
        dto.setGoodsName("iPhone 16");
        dto.setNormalPrice(new BigDecimal("5999.00"));

        goodsService.create(dto);

        ArgumentCaptor<Stock> captor = ArgumentCaptor.forClass(Stock.class);
        verify(stockMapper).insert(captor.capture());
        assertEquals(0, captor.getValue().getTotalStock());
        // 库存行同步写商品名快照：查库存表时不用联 t_goods 也能看懂是哪个商品
        assertEquals("iPhone 16", captor.getValue().getGoodsName());
        verify(bloomFilter).add(any());
    }

    @Test
    @DisplayName("更新商品：Cache Aside 删除缓存")
    void updateDeletesCache() {
        when(goodsMapper.selectById(1L)).thenReturn(goods);
        GoodsDTO dto = new GoodsDTO();
        dto.setGoodsName("new name");
        dto.setNormalPrice(new BigDecimal("1.00"));
        goodsService.update(1L, dto);
        verify(redis).delete(anyString());
    }

    @Test
    @DisplayName("更新商品：新名字同步刷到库存行与活动行的冗余列")
    void updateSyncsRedundantNames() {
        when(goodsMapper.selectById(1L)).thenReturn(goods);
        GoodsDTO dto = new GoodsDTO();
        dto.setGoodsName("renamed");
        dto.setNormalPrice(new BigDecimal("1.00"));

        goodsService.update(1L, dto);

        // t_stock 与 t_seckill_activity 都是"配置行"语义，必须反映当前名字
        ArgumentCaptor<Wrapper<Stock>> stockWrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(stockMapper).update(isNull(), stockWrapper.capture());
        assertTrue(((LambdaUpdateWrapper<Stock>) stockWrapper.getValue()).getSqlSet().contains("goods_name"));

        ArgumentCaptor<Wrapper<SeckillActivity>> actWrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(activityMapper).update(isNull(), actWrapper.capture());
        assertTrue(((LambdaUpdateWrapper<SeckillActivity>) actWrapper.getValue()).getSqlSet().contains("goods_name"));
    }
}
