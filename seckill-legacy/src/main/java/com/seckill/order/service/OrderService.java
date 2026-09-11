package com.seckill.order.service;

import com.seckill.order.entity.Order;

public interface OrderService {

    Order getByRequestId(String requestId);
}
