package com.seckill.goods.controller.internal;

import com.seckill.common.api.goods.ActivityInfoDTO;
import com.seckill.common.api.goods.DeductStockRequest;
import com.seckill.common.api.goods.RollbackStockRequest;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.common.result.Result;
import com.seckill.goods.entity.Goods;
import com.seckill.goods.entity.SeckillActivity;
import com.seckill.goods.mapper.GoodsMapper;
import com.seckill.goods.mapper.SeckillActivityMapper;
import com.seckill.goods.service.ActivityPreheatService;
import com.seckill.goods.service.StockOperationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品域内部接口（供 seckill-service 的 Feign 调用，网关不路由 /internal/**）。
 *
 * 拆分原则的落地点：seckill-service 禁止直接访问 goods 库的表，
 * 活动信息/商品名/扣减库存/回滚库存全部只能通过这些内部端点完成。
 * 业务失败统一 HTTP 200 + Result.code!=0，让 Feign 调用方按错误码透传语义。
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalGoodsController {

    private final GoodsMapper goodsMapper;
    private final SeckillActivityMapper activityMapper;
    private final ActivityPreheatService preheatService;
    private final StockOperationService stockOperationService;

    /** 商品名快照：seckill 组装 MQ 消息时使用，商品不存在返回 data:null（调用方降级为空串） */
    @GetMapping("/goods/{id}/name")
    public Result<String> goodsName(@PathVariable Long id) {
        Goods goods = goodsMapper.selectById(id);
        return Result.ok(goods == null ? null : goods.getGoodsName());
    }

    /** 活动信息：seckill 下单链路在 Redis 活动缓存 miss 时兜底调用；对账任务判断活动是否结束 */
    @GetMapping("/activity/{id}")
    public Result<ActivityInfoDTO> activity(@PathVariable Long id) {
        SeckillActivity activity = activityMapper.selectById(id);
        if (activity == null) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
        return Result.ok(toDTO(activity));
    }

    /** 补预热：seckill 侧 Lua 返回 -3（未预热）时触发，只传 id，goods 侧自己查库预热 */
    @PostMapping("/activity/{id}/preheat")
    public Result<Void> preheat(@PathVariable Long id) {
        SeckillActivity activity = activityMapper.selectById(id);
        if (activity == null) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
        preheatService.preheat(activity);
        return Result.ok();
    }

    /** 条件扣减 DB 库存（幂等，requestId 与下单请求一致） */
    @PostMapping("/stock/deduct")
    public Result<Void> deduct(@RequestBody DeductStockRequest request) {
        stockOperationService.deduct(request);
        return Result.ok();
    }

    /** 回滚 DB 库存（幂等，插单失败补偿与对账兜底共用） */
    @PostMapping("/stock/rollback")
    public Result<Void> rollback(@RequestBody RollbackStockRequest request) {
        stockOperationService.rollback(request);
        return Result.ok();
    }

    /** 实体 → 跨服务 DTO：字段一一对应，DTO 不带 MyBatis-Plus 注解，与表结构解耦 */
    private ActivityInfoDTO toDTO(SeckillActivity activity) {
        ActivityInfoDTO dto = new ActivityInfoDTO();
        dto.setId(activity.getId());
        dto.setActivityName(activity.getActivityName());
        dto.setGoodsId(activity.getGoodsId());
        dto.setSeckillPrice(activity.getSeckillPrice());
        dto.setTotalStock(activity.getTotalStock());
        dto.setStartTime(activity.getStartTime());
        dto.setEndTime(activity.getEndTime());
        dto.setStatus(activity.getStatus());
        dto.setIsHot(activity.getIsHot());
        return dto;
    }
}
