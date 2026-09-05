package com.seckill.order.controller;

import com.seckill.common.result.Result;
import com.seckill.order.entity.Order;
import com.seckill.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping("/query")
    public Result<Order> query(@RequestParam String requestId) {
        return Result.ok(orderService.getByRequestId(requestId));
    }
}
