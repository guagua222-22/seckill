package com.seckill.goods.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.seckill.common.result.Result;
import com.seckill.goods.dto.ActivityDTO;
import com.seckill.goods.entity.SeckillActivity;
import com.seckill.goods.service.ActivityPreheatService;
import com.seckill.goods.service.SeckillActivityService;
import com.seckill.goods.mapper.SeckillActivityMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final ActivityPreheatService preheatService;
    private final SeckillActivityMapper activityMapper;

    @PostMapping
    public Result<SeckillActivity> create(@Valid @RequestBody ActivityDTO dto) {
        SeckillActivity a = activityService.create(dto);
        // 创建后立即预热：前端不用等 30s 定时任务，页面直接显示库存数
        preheatService.preheat(a);
        return Result.ok(a);
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

    /** 一键预热：手工触发单个活动的 Redis 库存灌入 + 活动信息缓存写入。幂等（SETNX） */
    @PostMapping("/{id}/preheat")
    public Result<String> preheat(@PathVariable Long id) {
        SeckillActivity a = activityService.getById(id);
        if (a == null) return Result.fail(404, "活动不存在");
        preheatService.preheat(a);
        return Result.ok("已预热: seckill:stock:" + id);
    }

    /** 切换 is_hot 标记：方便 M6 热点隔离 A/B 实验（不用 Navicat 改库） */
    @PutMapping("/{id}/hot")
    public Result<SeckillActivity> toggleHot(@PathVariable Long id) {
        SeckillActivity a = activityService.getById(id);
        if (a == null) return Result.fail(404, "活动不存在");
        a.setIsHot(Integer.valueOf(1).equals(a.getIsHot()) ? 0 : 1);
        activityMapper.updateById(a);
        // 立即重跑预热：刷新热点标记 + 活动缓存
        preheatService.preheat(a);
        return Result.ok(a);
    }
}
