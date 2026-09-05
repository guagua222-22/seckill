package com.seckill.seckill.service.impl;

import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.goods.entity.SeckillActivity;
import com.seckill.goods.mapper.SeckillActivityMapper;
import com.seckill.goods.service.ActivityPreheatService;
import com.seckill.seckill.dto.SeckillOrderDTO;
import com.seckill.user.entity.User;
import com.seckill.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 秒杀下单服务单测（M3 版）。
 * 重点覆盖：Lua 各返回码分支、DB 失败回滚 Redis、未预热补预热重试、Redis 故障降级。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeckillOrderServiceImplTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private DefaultRedisScript<Long> script;
    @Mock
    private SeckillActivityMapper activityMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private ActivityPreheatService preheatService;
    @Mock
    private DbOrderWriter dbOrderWriter;

    @InjectMocks
    private SeckillOrderServiceImpl seckillOrderService;

    private SeckillActivity activity;
    private User user;

    @BeforeEach
    void setUp() {
        // 进行中的活动
        activity = new SeckillActivity();
        activity.setId(100L);
        activity.setGoodsId(1L);
        activity.setSeckillPrice(new BigDecimal("9.90"));
        activity.setStartTime(LocalDateTime.now().minusHours(1));
        activity.setEndTime(LocalDateTime.now().plusHours(1));

        user = new User();
        user.setId(1L);

        // 活动信息缓存 miss → 回 DB；Redis 值操作返回 null
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(activityMapper.selectById(100L)).thenReturn(activity);
        when(userMapper.selectById(1L)).thenReturn(user);
    }

    private SeckillOrderDTO dto() {
        SeckillOrderDTO dto = new SeckillOrderDTO();
        dto.setUserId(1L);
        dto.setActivityId(100L);
        dto.setRequestId("req-1");
        return dto;
    }

    @Test
    @DisplayName("活动未开始：不碰 Redis 直接拒绝")
    void activityNotStarted() {
        activity.setStartTime(LocalDateTime.now().plusHours(1));
        activity.setEndTime(LocalDateTime.now().plusHours(2));
        BizException e = assertThrows(BizException.class, () -> seckillOrderService.createOrder(dto()));
        assertEquals(ErrorCode.ACTIVITY_NOT_STARTED.getCode(), e.getCode());
        verify(redis, never()).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @DisplayName("Lua 返回 -1：已抢过")
    void luaDuplicate() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(-1L);
        BizException e = assertThrows(BizException.class, () -> seckillOrderService.createOrder(dto()));
        assertEquals(ErrorCode.ALREADY_ORDERED.getCode(), e.getCode());
    }

    @Test
    @DisplayName("Lua 返回 -2：库存不足")
    void luaStockEmpty() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(-2L);
        BizException e = assertThrows(BizException.class, () -> seckillOrderService.createOrder(dto()));
        assertEquals(ErrorCode.STOCK_NOT_ENOUGH.getCode(), e.getCode());
    }

    @Test
    @DisplayName("Lua 成功 + DB 落单成功")
    void luaSuccess() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        when(dbOrderWriter.writeOrder(any(), any())).thenReturn(888L);
        assertEquals(888L, seckillOrderService.createOrder(dto()));
    }

    @Test
    @DisplayName("Lua 成功但 DB 落单失败：回滚 Redis 预扣后抛出原异常")
    void dbFailRollbackRedis() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        when(dbOrderWriter.writeOrder(any(), any()))
                .thenThrow(new BizException(ErrorCode.STOCK_NOT_ENOUGH));
        // 回滚路径需要 Set 操作：SREM 返回 1（确实登记过）才会 INCR 库存
        org.springframework.data.redis.core.SetOperations<String, String> setOps =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.SetOperations.class);
        when(redis.opsForSet()).thenReturn(setOps);
        when(setOps.remove(anyString(), any())).thenReturn(1L);

        BizException e = assertThrows(BizException.class, () -> seckillOrderService.createOrder(dto()));
        assertEquals(ErrorCode.STOCK_NOT_ENOUGH.getCode(), e.getCode());
        verify(redis).opsForSet();
    }

    @Test
    @DisplayName("Lua 返回 -3：补预热后重试成功")
    void luaNotPreheatedThenRetry() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(-3L, 1L);
        when(dbOrderWriter.writeOrder(any(), any())).thenReturn(777L);
        assertEquals(777L, seckillOrderService.createOrder(dto()));
        verify(preheatService).preheat(activity);
    }

    @Test
    @DisplayName("Redis 故障：降级 DB 直写链路")
    void redisDownFallback() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisSystemException("connection refused", new RuntimeException()));
        when(dbOrderWriter.writeOrder(any(), any())).thenReturn(666L);
        assertEquals(666L, seckillOrderService.createOrder(dto()));
    }

    @Test
    @DisplayName("用户不存在：快速失败")
    void userNotFound() {
        when(userMapper.selectById(1L)).thenReturn(null);
        BizException e = assertThrows(BizException.class, () -> seckillOrderService.createOrder(dto()));
        assertEquals(ErrorCode.USER_NOT_FOUND.getCode(), e.getCode());
    }
}
