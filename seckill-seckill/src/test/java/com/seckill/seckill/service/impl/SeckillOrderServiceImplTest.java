package com.seckill.seckill.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.common.api.goods.ActivityInfoDTO;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.common.result.Result;
import com.seckill.seckill.dto.SeckillOrderDTO;
import com.seckill.seckill.entity.SeckillRecord;
import com.seckill.seckill.feign.GoodsClient;
import com.seckill.seckill.feign.UserClient;
import com.seckill.seckill.mapper.LocalMessageMapper;
import com.seckill.seckill.mapper.SeckillRecordMapper;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 秒杀下单服务单测（M5 微服务版）。
 * 重点覆盖：幂等快路径、Lua 各分支、流水消息事务写入、DuplicateKey 回滚、Redis 故障降级。
 * M5 变化：原 GoodsMapper/UserMapper/SeckillActivityMapper/ActivityPreheatService 的
 * 跨域 mock 全部换成 mock Feign client（GoodsClient/UserClient），stub 返回 Result。
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
    private GoodsClient goodsClient;
    @Mock
    private UserClient userClient;
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

    private ActivityInfoDTO activity;

    @BeforeEach
    void setUp() {
        activity = new ActivityInfoDTO();
        activity.setId(100L);
        activity.setActivityName("测试活动");
        activity.setGoodsId(1L);
        activity.setSeckillPrice(new BigDecimal("9.90"));
        activity.setStartTime(LocalDateTime.now().minusHours(1));
        activity.setEndTime(LocalDateTime.now().plusHours(1));

        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        // Feign 兜底返回活动信息（Redis miss 场景）
        when(goodsClient.activity(100L)).thenReturn(Result.ok(activity));
        when(goodsClient.goodsName(1L)).thenReturn(Result.ok("测试商品"));
        // 用户名接口同时承担"存在性校验 + 用户名快照"两个职责
        when(userClient.username(1L)).thenReturn(Result.ok("test_1"));
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

        verify(recordMessageWriter).write(eq("req-1"), eq(1L), eq("test_1"),
                eq(100L), eq("测试活动"), anyString(), anyString());
        // asyncSend 有多个重载，显式 any(Object.class) 匹配 (String, Object, SendCallback) 重载
        verify(rocketMQTemplate).asyncSend(anyString(), any(Object.class),
                any(org.apache.rocketmq.client.producer.SendCallback.class));
    }

    @Test
    @DisplayName("流水写入撞唯一索引：回滚预扣并提示排队中")
    void writeDuplicateKeyRollback() {
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_request"))
                .when(recordMessageWriter).write(anyString(), any(), any(), any(), any(), anyString(), anyString());

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
        verify(dbOrderWriter).writeOrder(any(), any(), anyString(), eq("test_1"));
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
    @DisplayName("用户不存在：Feign 返回 null 用户名直接拒绝")
    void userNotFound() {
        when(userClient.username(1L)).thenReturn(Result.<String>ok(null));
        BizException e = assertThrows(BizException.class, () -> seckillOrderService.createOrder(dto()));
        assertEquals(ErrorCode.USER_NOT_FOUND.getCode(), e.getCode());
        verify(redis, never()).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @DisplayName("活动信息 Feign 兜底：Redis miss 时调 goods 接口（错误码透传）")
    void activityFeignFallback() {
        when(goodsClient.activity(100L)).thenReturn(Result.fail(ErrorCode.ACTIVITY_NOT_FOUND));
        BizException e = assertThrows(BizException.class, () -> seckillOrderService.createOrder(dto()));
        // Feign 返回的业务码原样透传
        assertEquals(ErrorCode.ACTIVITY_NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    @DisplayName("M5 回归修复：Lua 成功后 buildMessage 的 Feign 失败 → 回滚预扣，不留幽灵扣减")
    void buildMessageFeignFailureRollsBack() {
        // Lua 预扣已成功（库存-1、用户进已抢集合）
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        // 商品名 Feign 失败（goods-service 挂掉的场景，ErrorDecoder 会转 BizException(500)）
        when(goodsClient.goodsName(1L))
                .thenThrow(new BizException(ErrorCode.INTERNAL_ERROR.getCode(), "依赖服务暂不可用"));

        BizException e = assertThrows(BizException.class, () -> seckillOrderService.createOrder(dto()));
        assertEquals(ErrorCode.INTERNAL_ERROR.getCode(), e.getCode());

        // 关键断言：预扣必须回滚（实验2 的幽灵扣减就是这一步缺失导致的）
        verify(stockRollback).rollback(1L, 100L);
        // 且流水/消息都没写、MQ 没发——"要么排队成功，要么完全没发生"
        verify(recordMessageWriter, never()).write(anyString(), any(), any(), any(), any(), anyString(), anyString());
        verify(rocketMQTemplate, never()).asyncSend(anyString(), any(Object.class),
                any(org.apache.rocketmq.client.producer.SendCallback.class));
    }
}
