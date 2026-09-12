package com.seckill.seckill.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.seckill.order.entity.Order;
import com.seckill.seckill.order.mapper.OrderMapper;
import com.seckill.seckill.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

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

    @Override
    public List<Order> listRecent(int limit) {
        // limit 夹在 [1,200]：防止前端传 0 查不到东西，或传超大值把整表拉出来
        int size = Math.max(1, Math.min(limit, 200));
        return orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .orderByDesc(Order::getCreateTime)
                .orderByDesc(Order::getId)
                .last("LIMIT " + size));
    }
}
