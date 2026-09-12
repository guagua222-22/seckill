package com.seckill.goods.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.common.api.goods.ActivityInfoDTO;
import com.seckill.common.redis.RedisKeys;
import com.seckill.goods.entity.SeckillActivity;
import com.seckill.goods.mapper.SeckillActivityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 活动预热单测，重点守住 goods 与 seckill 之间的共享 Redis 契约。
 *
 * 为什么要专门测这个：seckill 侧把这段 JSON 直接反序列化成 ActivityInfoDTO，
 * 少写一个字段不会报错、只会静默变成 null（activityName 为空串的线上问题就是这么来的）。
 * 所以这里断言"对端能读回完整的活动信息"，而不是只断言"调用了 set"。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActivityPreheatServiceImplTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private SeckillActivityMapper activityMapper;

    /** 真实 ObjectMapper（注册时间模块），与两个服务运行时的序列化行为一致 */
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @InjectMocks
    private ActivityPreheatServiceImpl preheatService;

    private SeckillActivity activity;

    @BeforeEach
    void setUp() {
        activity = new SeckillActivity();
        activity.setId(100L);
        activity.setActivityName("双11秒杀");
        activity.setGoodsId(1L);
        activity.setGoodsName("iPhone 16");
        activity.setSeckillPrice(new BigDecimal("9.90"));
        activity.setTotalStock(100);
        activity.setStartTime(LocalDateTime.now().minusMinutes(1));
        activity.setEndTime(LocalDateTime.now().plusHours(1));

        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("预热：库存用 SETNX 幂等写入，不覆盖已扣减的计数")
    void preheatUsesSetnxForStock() {
        when(valueOps.setIfAbsent(anyString(), anyString())).thenReturn(true);

        preheatService.preheat(activity);

        verify(valueOps).setIfAbsent(eq(RedisKeys.stock(100L)), eq("100"));
    }

    @Test
    @DisplayName("预热：活动信息 JSON 能被对端读回完整字段（含两个名字快照）")
    void preheatWritesCompleteActivityInfo() throws Exception {
        when(valueOps.setIfAbsent(anyString(), anyString())).thenReturn(true);

        preheatService.preheat(activity);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(RedisKeys.activityInfo(100L)), json.capture(), any(Duration.class));

        ActivityInfoDTO read = objectMapper.readValue(json.getValue(), ActivityInfoDTO.class);
        assertEquals(100L, read.getId());
        // 名字快照是下单链路写冗余列的数据源，缺了对端只会拿到 null
        assertEquals("双11秒杀", read.getActivityName());
        assertEquals("iPhone 16", read.getGoodsName());
        assertEquals(1L, read.getGoodsId());
        assertEquals(new BigDecimal("9.90"), read.getSeckillPrice());
        assertNotNull(read.getStartTime());
        assertNotNull(read.getEndTime());
    }

    @Test
    @DisplayName("批量预热：时间窗内的活动逐个预热")
    void preheatUpcomingCoversWindow() {
        SeckillActivity other = new SeckillActivity();
        other.setId(200L);
        other.setActivityName("第二场");
        other.setGoodsId(2L);
        other.setGoodsName("iPad");
        other.setSeckillPrice(new BigDecimal("19.90"));
        other.setTotalStock(50);
        other.setStartTime(LocalDateTime.now().plusMinutes(3));
        other.setEndTime(LocalDateTime.now().plusHours(2));
        when(activityMapper.selectList(any(Wrapper.class))).thenReturn(List.of(activity, other));
        when(valueOps.setIfAbsent(anyString(), anyString())).thenReturn(true);

        preheatService.preheatUpcoming();

        verify(valueOps, times(2)).set(anyString(), anyString(), any(Duration.class));
        verify(valueOps).set(eq(RedisKeys.activityInfo(200L)), anyString(), any(Duration.class));
    }
}
