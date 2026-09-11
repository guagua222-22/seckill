package com.seckill.seckill.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.goods.entity.Goods;
import com.seckill.goods.entity.SeckillActivity;
import com.seckill.goods.mapper.GoodsMapper;
import com.seckill.goods.mapper.SeckillActivityMapper;
import com.seckill.goods.service.ActivityPreheatService;
import com.seckill.seckill.dto.SeckillOrderDTO;
import com.seckill.seckill.entity.SeckillRecord;
import com.seckill.seckill.mapper.LocalMessageMapper;
import com.seckill.seckill.mapper.SeckillRecordMapper;
import com.seckill.user.entity.User;
import com.seckill.user.mapper.UserMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 秒杀下单服务单测（M4 异步版）。
 * 重点覆盖：幂等快路径、Lua 各分支、流水消息事务写入、DuplicateKey 回滚、Redis 故障降级。
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
    private GoodsMapper goodsMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private ActivityPreheatService preheatService;
    @Mock
    private DbOrderWriter dbOrderWriter;
    @Mock
    private RecordMessageWriter recordMessageWriter;
    @Mock
    private SeckillRecordMapper recordMapper;
    @Mock
    private LocalMessageMapper messageMapper;
    @Mock
    private RocketMQTemplate rocketMQTemplate;
    @Mock
    private RedisStockRollback stockRollback;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @InjectMocks
    private SeckillOrderServiceImpl seckillOrderService;

    private SeckillActivity activity;
    private User user;

    @BeforeEach
    void setUp() {
        activity = new SeckillActivity();
        activity.setId(100L);
        activity.setGoodsId(1L);
        activity.setSeckillPrice(new BigDecimal("9.90"));
        activity.setStartTime(LocalDateTime.now().minusHours(1));
        activity.setEndTime(LocalDateTime.now().plusHours(1));

        user = new User();
        user.setId(1L);

        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(activityMapper.selectById(100L)).thenReturn(activity);
        Goods goods = new Goods();
        goods.setGoodsName("测试商品");
        when(goodsMapper.selectById(1L)).thenReturn(goods);
        when(userMapper.selectById(1L)).thenReturn(user);
        when(recordMapper.selectOne(any())).thenReturn(null); // 幂等快路径默认未命中
        ReflectionTestUtils.setField(seckillOrderService, "topic", "seckill-order-topic");
    }

    private SeckillOrderDTO dto() {
        SeckillOrderDTO dto = new SeckillOrderDTO();
        dto.setUserId(1L);
        dto.setActivityId(100L);
        dto.setRequestId("req-1");
        return dto;
    }

    @Test
    @DisplayName("幂等快路径：同一 requestId 排队中，重复提交被拦")
    void idempotentQueued() {
        SeckillRecord record = new SeckillRecord();
        record.setRequestId("req-1");
        record.setStatus(0);
        when(recordMapper.selectOne(any())).thenReturn(record);

        BizException e = assertThrows(BizException.class, () -> seckillOrderService.createOrder(dto()));
        assertEquals(ErrorCode.ALREADY_ORDERED.getCode(), e.getCode());
        verify(redis, never()).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @DisplayName("幂等快路径：已下单，重复提交被拦")
    void idempotentOrdered() {
        SeckillRecord record = new SeckillRecord();
        record.setRequestId("req-1");
        record.setStatus(1);
        when(recordMapper.selectOne(any())).thenReturn(record);

        BizException e = assertThrows(BizException.class, () -> seckillOrderService.createOrder(dto()));
        assertEquals(ErrorCode.ALREADY_ORDERED.getCode(), e.getCode());
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
    @DisplayName("Lua 成功：事务落流水消息并异步发送，立即返回（排队中）")
    void luaSuccessAsyncSend() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        assertDoesNotThrow(() -> seckillOrderService.createOrder(dto()));

        verify(recordMessageWriter).write(eq("req-1"), eq(1L), eq(100L), anyString(), anyString());
        // asyncSend 有多个重载，显式 any(Object.class) 匹配 (String, Object, SendCallback) 重载
        verify(rocketMQTemplate).asyncSend(anyString(), any(Object.class),
                any(org.apache.rocketmq.client.producer.SendCallback.class));
    }

    @Test
    @DisplayName("流水写入撞唯一索引：回滚预扣并提示排队中")
    void writeDuplicateKeyRollback() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_request"))
                .when(recordMessageWriter).write(anyString(), any(), any(), anyString(), anyString());

        BizException e = assertThrows(BizException.class, () -> seckillOrderService.createOrder(dto()));
        assertEquals(ErrorCode.ALREADY_ORDERED.getCode(), e.getCode());
        verify(stockRollback).rollback(1L, 100L);
    }

    @Test
    @DisplayName("Redis 故障：降级 DB 同步直写")
    void redisDownFallback() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisSystemException("connection refused", new RuntimeException()));

        assertDoesNotThrow(() -> seckillOrderService.createOrder(dto()));
        verify(dbOrderWriter).writeOrder(any(), any());
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
}
