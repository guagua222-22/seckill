package com.seckill.seckill.order.service;

import com.seckill.seckill.order.entity.Order;

public interface OrderService {

    Order getByRequestId(String requestId);
}
