package com.seckill.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.order.entity.Order;
import com.seckill.order.mapper.OrderMapper;
import com.seckill.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 订单查询实现：走 uk_request_id 唯一索引，一次命中。
 */
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderMapper orderMapper;

    @Override
    public Order getByRequestId(String requestId) {
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>().eq(Order::getRequestId, requestId));
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }
}
