package com.seckill.seckill.order.controller;

import com.seckill.common.result.Result;
import com.seckill.seckill.order.entity.Order;
import com.seckill.seckill.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单查询接口。
 * 查单按 requestId 而不是 orderId：前端下单拿到的是 requestId，
 * 轮询查单时用它幂等地找到"我这次请求对应的订单"。
 */
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @GetMapping("/query")
    public Result<Order> query(@RequestParam String requestId) {
        return Result.ok(orderService.getByRequestId(requestId));
    }

    /** 最近订单快照列表：验证页展示用，字段含用户名/活动名/商品名冗余快照，零跨库 join */
    @GetMapping("/list")
    public Result<List<Order>> list(@RequestParam(defaultValue = "20") int limit) {
        return Result.ok(orderService.listRecent(limit));
    }
}
