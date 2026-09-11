package com.seckill.seckill.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.common.result.Result;
import com.seckill.seckill.dto.SeckillMessage;
import com.seckill.seckill.entity.SeckillRecord;
import com.seckill.seckill.feign.GoodsClient;
import com.seckill.seckill.mapper.SeckillRecordMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消费端核心逻辑单测（M5 微服务版）。
 * 覆盖：SETNX 去重（含拆分后"命中但未终态需重入"的新语义）、锁获取失败重试、
 * 落单成功、幂等成功（已下单）、库存不足回滚（含 DB 库存补偿）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeckillOrderConsumerServiceTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private DbOrderWriter dbOrderWriter;
    @Mock
    private GoodsClient goodsClient;
    @Mock
    private SeckillRecordMapper recordMapper;
    @Mock
    private RedisStockRollback stockRollback;

    @InjectMocks
    private SeckillOrderConsumerService consumerService;

    private SeckillMessage message;

    @BeforeAll
    static void initLambdaCache() {
        // 纯单测无 Spring 上下文，LambdaUpdateWrapper 需要手动初始化实体元信息缓存
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SeckillRecord.class);
    }

    @BeforeEach
    void setUp() throws InterruptedException {
        message = new SeckillMessage();
        message.setRequestId("req-1");
        message.setUserId(1L);
        message.setActivityId(100L);
        message.setGoodsId(1L);
        message.setGoodsName("iPhone");
        message.setPrice(new BigDecimal("9.90"));

        // 默认：SETNX 通过（首次消费）、锁获取成功
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(3, 10, TimeUnit.SECONDS)).thenReturn(true);
    }

    @Test
    @DisplayName("真正重复：SETNX 命中且流水已下单，拦截不落单")
    void duplicateMessageBlocked() throws InterruptedException {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        SeckillRecord record = new SeckillRecord();
        record.setStatus(1);
        when(recordMapper.selectOne(any(Wrapper.class))).thenReturn(record);

        assertDoesNotThrow(() -> consumerService.process(message));
        verify(dbOrderWriter, never()).writeOrder(any(), any(), any());
        verify(lock, never()).tryLock(3, 10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("M5 语义：SETNX 命中但流水排队中（上次中断），重入继续处理")
    void setnxHitButPendingReenters() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        SeckillRecord record = new SeckillRecord();
        record.setStatus(0); // 排队中 = 上次处理中断（如补偿失败后 MQ 重投）
        when(recordMapper.selectOne(any(Wrapper.class))).thenReturn(record);
        when(dbOrderWriter.writeOrder(any(), any(), anyString())).thenReturn(123L);

        assertDoesNotThrow(() -> consumerService.process(message));
        // 关键断言：重入后正常走落单，而不是被 SETNX 永久挡住
        verify(dbOrderWriter).writeOrder(any(), any(), any());
    }

    @Test
    @DisplayName("落单成功：流水推进到已下单")
    void successFlow() {
        when(dbOrderWriter.writeOrder(any(), any(), anyString())).thenReturn(123L);
        assertDoesNotThrow(() -> consumerService.process(message));
        verify(recordMapper).update(isNull(), any(Wrapper.class)); // 状态置 1
        verify(lock).unlock();
    }

    @Test
    @DisplayName("已下单（幂等成功）：状态对齐，不抛异常")
    void alreadyOrderedIdempotent() {
        when(dbOrderWriter.writeOrder(any(), any(), anyString()))
                .thenThrow(new BizException(ErrorCode.ALREADY_ORDERED));
        assertDoesNotThrow(() -> consumerService.process(message));
        verify(recordMapper).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("库存不足：回滚预扣 + 补偿 DB 库存 + 流水置已回滚，不抛异常")
    void stockNotEnoughRollback() {
        when(dbOrderWriter.writeOrder(any(), any(), anyString()))
                .thenThrow(new BizException(ErrorCode.STOCK_NOT_ENOUGH));
        when(goodsClient.rollbackStock(any())).thenReturn(Result.ok());

        assertDoesNotThrow(() -> consumerService.process(message));
        verify(stockRollback).rollback(1L, 100L);
        verify(goodsClient).rollbackStock(any()); // M5 新增：跨库补偿 DB 库存
        verify(recordMapper).update(isNull(), any(Wrapper.class)); // 状态置 2
    }

    @Test
    @DisplayName("获取用户锁超时：抛异常触发 MQ 重试")
    void lockTimeoutRetry() throws InterruptedException {
        when(lock.tryLock(3, 10, TimeUnit.SECONDS)).thenReturn(false);
        assertThrows(RuntimeException.class, () -> consumerService.process(message));
        verify(dbOrderWriter, never()).writeOrder(any(), any(), anyString());
    }
}
