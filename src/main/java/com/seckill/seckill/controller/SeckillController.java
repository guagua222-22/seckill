package com.seckill.seckill.controller;

import com.seckill.common.result.Result;
import com.seckill.seckill.dto.SeckillOrderDTO;
import com.seckill.seckill.service.SeckillOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秒杀接口层：整个项目的核心入口。
 * M2 为 DB 同步版，M3 起内部升级为 Redis Lua 预扣，接口签名保持不变——
 * 这就是"接口不变、实现演进"的工程实践。
 */
@RestController
@RequestMapping("/api/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillOrderService seckillOrderService;

    @PostMapping("/order")
    public Result<Long> order(@Valid @RequestBody SeckillOrderDTO dto) {
        return Result.ok(seckillOrderService.createOrder(dto));
    }

    /** Redis 实时剩余库存（前端活动列表展示，-1 表示未预热） */
    @GetMapping("/stock/{activityId}")
    public Result<Integer> redisStock(@PathVariable Long activityId) {
        return Result.ok(seckillOrderService.getRedisStock(activityId));
    }
}
