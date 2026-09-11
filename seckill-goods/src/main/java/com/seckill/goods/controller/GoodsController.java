package com.seckill.goods.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.seckill.common.result.Result;
import com.seckill.goods.dto.GoodsDTO;
import com.seckill.goods.entity.Goods;
import com.seckill.goods.service.GoodsService;
import com.seckill.goods.vo.GoodsDetailVO;
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
 * 商品接口层。
 * 路径约定：所有接口从第一天就带 /api/{域} 前缀，
 * M5 拆微服务时网关按前缀路由即可，前端和测试脚本零改动。
 */
@RestController
@RequestMapping("/api/goods")
@RequiredArgsConstructor
public class GoodsController {

    private final GoodsService goodsService;

    @PostMapping
    public Result<Goods> create(@Valid @RequestBody GoodsDTO dto) {
        return Result.ok(goodsService.create(dto));
    }

    @PutMapping("/{id}")
    public Result<Goods> update(@PathVariable Long id, @Valid @RequestBody GoodsDTO dto) {
        return Result.ok(goodsService.update(id, dto));
    }

    @GetMapping("/{id}")
    public Result<GoodsDetailVO> detail(@PathVariable Long id) {
        return Result.ok(goodsService.getDetail(id));
    }

    @GetMapping("/page")
    public Result<Page<Goods>> page(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int size) {
        return Result.ok(goodsService.page(page, size));
    }
}
