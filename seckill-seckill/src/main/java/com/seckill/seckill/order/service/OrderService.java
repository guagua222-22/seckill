package com.seckill.seckill.order.service;

import com.seckill.seckill.order.entity.Order;

import java.util.List;

public interface OrderService {

    Order getByRequestId(String requestId);

    /** 最近订单列表（前端"订单快照"展示用，按创建时间倒序） */
    List<Order> listRecent(int limit);
}
