package com.seckill.goods.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.seckill.common.result.Result;
import com.seckill.goods.dto.ActivityDTO;
import com.seckill.goods.entity.SeckillActivity;
import com.seckill.goods.service.SeckillActivityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秒杀活动接口层（单体阶段挂在 goods 域下，M5 拆分后归 goods-service）。
 */
@RestController
@RequestMapping("/api/goods/activity")
@RequiredArgsConstructor
public class SeckillActivityController {

    private final SeckillActivityService activityService;

    @PostMapping
    public Result<SeckillActivity> create(@Valid @RequestBody ActivityDTO dto) {
        return Result.ok(activityService.create(dto));
    }

    @GetMapping("/{id}")
    public Result<SeckillActivity> detail(@PathVariable Long id) {
        return Result.ok(activityService.getById(id));
    }

    @GetMapping("/page")
    public Result<Page<SeckillActivity>> page(@RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "10") int size) {
        return Result.ok(activityService.page(page, size));
    }
}
