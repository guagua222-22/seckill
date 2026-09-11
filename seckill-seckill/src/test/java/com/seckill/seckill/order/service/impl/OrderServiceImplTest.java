package com.seckill.seckill.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.seckill.order.entity.Order;
import com.seckill.seckill.order.mapper.OrderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 订单查询单测（order 域并入 seckill-service 后补的覆盖率）。
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderMapper orderMapper;

    @InjectMocks
    private OrderServiceImpl orderService;

    @Test
    @DisplayName("按 requestId 查单：命中返回订单")
    void queryHit() {
        Order order = new Order();
        order.setRequestId("req-1");
        order.setOrderNo("no-1");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        Order result = orderService.getByRequestId("req-1");
        assertEquals("no-1", result.getOrderNo());
    }

    @Test
    @DisplayName("按 requestId 查单：未命中抛订单不存在")
    void queryMiss() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> orderService.getByRequestId("req-1"));
        assertEquals(ErrorCode.ORDER_NOT_FOUND.getCode(), e.getCode());
    }
}
