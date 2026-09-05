package com.seckill.seckill.controller;

import com.seckill.common.result.Result;
import com.seckill.seckill.dto.SeckillOrderDTO;
import com.seckill.seckill.service.SeckillOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/seckill")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillOrderService seckillOrderService;

    @PostMapping("/order")
    public Result<Long> order(@Valid @RequestBody SeckillOrderDTO dto) {
        return Result.ok(seckillOrderService.createOrder(dto));
    }
}
