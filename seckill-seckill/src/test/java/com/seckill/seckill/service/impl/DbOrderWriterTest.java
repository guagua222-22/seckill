package com.seckill.seckill.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.seckill.common.api.goods.ActivityInfoDTO;
import com.seckill.common.api.goods.DeductStockRequest;
import com.seckill.common.api.goods.RollbackStockRequest;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.common.result.Result;
import com.seckill.seckill.dto.SeckillOrderDTO;
import com.seckill.seckill.feign.GoodsClient;
import com.seckill.seckill.order.entity.Order;
import com.seckill.seckill.order.mapper.OrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DbOrderWriter 单测（M5 跨库补偿核心）：
 * 一人一单预检、Feign 扣库存、唯一索引冲突补偿回滚、Feign 失败透传。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DbOrderWriterTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private GoodsClient goodsClient;

    @InjectMocks
    private DbOrderWriter dbOrderWriter;

    private SeckillOrderDTO dto;
    private ActivityInfoDTO activity;

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Order.class);
    }

    @BeforeEach
    void setUp() {
        dto = new SeckillOrderDTO();
        dto.setUserId(1L);
        dto.setActivityId(100L);
        dto.setRequestId("req-1");

        activity = new ActivityInfoDTO();
        activity.setId(100L);
        activity.setGoodsId(10L);
        activity.setSeckillPrice(new BigDecimal("9.90"));
    }

    @Test
    @DisplayName("一人一单预检命中：不扣库存直接拒绝")
    void precheckAlreadyOrdered() {
        when(orderMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        BizException e = assertThrows(BizException.class,
                () -> dbOrderWriter.writeOrder(dto, activity, "iPhone"));
        assertEquals(ErrorCode.ALREADY_ORDERED.getCode(), e.getCode());
        verify(goodsClient, never()).deductStock(any(DeductStockRequest.class));
    }

    @Test
    @DisplayName("Feign 扣库存返回库存不足：错误码透传")
    void deductStockNotEnough() {
        when(orderMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(goodsClient.deductStock(any(DeductStockRequest.class)))
                .thenReturn(Result.fail(ErrorCode.STOCK_NOT_ENOUGH));

        BizException e = assertThrows(BizException.class,
                () -> dbOrderWriter.writeOrder(dto, activity, "iPhone"));
        assertEquals(ErrorCode.STOCK_NOT_ENOUGH.getCode(), e.getCode());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    @DisplayName("正常落单：先 Feign 扣库存，再本地插订单")
    void writeSuccess() {
        when(orderMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(goodsClient.deductStock(any(DeductStockRequest.class))).thenReturn(Result.ok());

        dbOrderWriter.writeOrder(dto, activity, "iPhone");

        verify(goodsClient).deductStock(any(DeductStockRequest.class));
        // mock 的 insert 不会回填雪花 ID，用参数捕获断言落单内容
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(captor.capture());
        Order order = captor.getValue();
        assertEquals("req-1", order.getRequestId());
        assertEquals(1L, order.getUserId());
        assertEquals(100L, order.getActivityId());
        assertEquals("iPhone", order.getGoodsName());
        assertEquals(new BigDecimal("9.90"), order.getPrice());
        verify(goodsClient, never()).rollbackStock(any(RollbackStockRequest.class));
    }

    @Test
    @DisplayName("插单撞唯一索引：同步补偿库存（幂等），抛已抢过")
    void duplicateKeyCompensates() {
        when(orderMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(goodsClient.deductStock(any(DeductStockRequest.class))).thenReturn(Result.ok());
        when(orderMapper.insert(any(Order.class))).thenThrow(new DuplicateKeyException("uk_user_activity"));
        when(goodsClient.rollbackStock(any(RollbackStockRequest.class))).thenReturn(Result.ok());

        BizException e = assertThrows(BizException.class,
                () -> dbOrderWriter.writeOrder(dto, activity, "iPhone"));
        assertEquals(ErrorCode.ALREADY_ORDERED.getCode(), e.getCode());
        // 关键断言：唯一键冲突后必须补偿回滚 DB 库存
        verify(goodsClient).rollbackStock(any(RollbackStockRequest.class));
    }

    @Test
    @DisplayName("补偿回滚失败：主异常仍是已抢过，补偿失败只记日志不抛出")
    void rollbackFailureDoesNotMask() {
        when(orderMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(goodsClient.deductStock(any(DeductStockRequest.class))).thenReturn(Result.ok());
        when(orderMapper.insert(any(Order.class))).thenThrow(new DuplicateKeyException("uk_user_activity"));
        when(goodsClient.rollbackStock(any(RollbackStockRequest.class)))
                .thenThrow(new RuntimeException("goods-service down"));

        BizException e = assertThrows(BizException.class,
                () -> dbOrderWriter.writeOrder(dto, activity, "iPhone"));
        assertEquals(ErrorCode.ALREADY_ORDERED.getCode(), e.getCode());
    }
}
