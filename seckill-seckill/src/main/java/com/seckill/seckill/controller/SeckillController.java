package com.seckill.seckill.controller;

import com.seckill.common.result.ErrorCode;
import com.seckill.common.result.Result;
import com.seckill.seckill.config.SentinelRuleConfig;
import com.seckill.seckill.dto.SeckillOrderDTO;
import com.seckill.seckill.sentinel.SentinelGuard;
import com.seckill.seckill.service.SeckillOrderService;
import com.seckill.seckill.metrics.OrderAdmissionMetrics;
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
    private final OrderAdmissionMetrics admissionMetrics;

    /**
     * M4 起为异步排队模式：立即返回"排队中"（code=0），
     * 前端轮询 GET /api/order/query?requestId= 获取最终下单结果。
     *
     * M6 起入口挂 Sentinel：总 QPS 流控 + 按 activityId 的热点参数限流，
     * 被规则拒绝时抛 RATE_LIMITED（429 语义），全局异常处理统一转 Result。
     */
    @PostMapping("/order")
    public Result<Void> order(@Valid @RequestBody SeckillOrderDTO dto) {
        // 手动埋点而非注解：热点参数是 DTO 里的 activityId，注解按形参下标取不到嵌套字段
        admissionMetrics.record(() -> SentinelGuard.call(SentinelRuleConfig.RES_CREATE_ORDER, ErrorCode.RATE_LIMITED,
                () -> {
                    seckillOrderService.createOrder(dto);
                    return null;
                },
                dto.getActivityId()));
        return Result.ok();
    }

    /** Redis 实时剩余库存（前端活动列表展示，-1 表示未预热） */
    @GetMapping("/stock/{activityId}")
    public Result<Integer> redisStock(@PathVariable Long activityId) {
        return Result.ok(seckillOrderService.getRedisStock(activityId));
    }
}
