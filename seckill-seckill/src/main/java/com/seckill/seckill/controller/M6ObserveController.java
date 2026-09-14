package com.seckill.seckill.controller;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.seckill.common.result.Result;
import com.seckill.seckill.cache.HotActivityLocalCache;
import com.seckill.seckill.config.SentinelRuleConfig;
import com.seckill.seckill.service.SeckillOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M6 实验台观测接口（验证台前端面板用）。
 *
 * 把 seckill-service 本地的 Sentinel 规则、Caffeine 缓存指标、Redis 实时库存
 * 汇成一个 JSON 返回。前端每 3 秒轮询一次，用户不需要记住任何 curl 命令。
 *
 * 接口走 /api/seckill/m6/*，网关把 /api/seckill/** 路由到 seckill-service，
 * 所以验证台（跑在 8080）可以直接 fetch 这里的数据。
 */
@RestController
@RequestMapping("/api/seckill/m6")
@RequiredArgsConstructor
public class M6ObserveController {

    private final HotActivityLocalCache hotActivityLocalCache;
    private final SeckillOrderService seckillOrderService;

    /** 返回 4 组 M6 指标（Sentinel 规则 / Caffeine 缓存 / Redis 库存 / 控制台地址） */
    @GetMapping("/status")
    public Result<Map<String, Object>> status(@RequestParam(required = false) Long activityId) {
        Map<String, Object> m = new LinkedHashMap<>();

        // ── Sentinel 规则 ──
        Map<String, Object> sentinel = new LinkedHashMap<>();
        sentinel.put("flowRules", FlowRuleManager.getRules());          // QPS 流控
        sentinel.put("paramRules", ParamFlowRuleManager.getRules());    // 热点参数限流
        sentinel.put("degradeRules", DegradeRuleManager.getRules());    // 熔断降级
        sentinel.put("resources", Map.of(
                "入口闸门", SentinelRuleConfig.RES_CREATE_ORDER,
                "goods-activity", SentinelRuleConfig.RES_DEP_GOODS_ACTIVITY,
                "goods-name", SentinelRuleConfig.RES_DEP_GOODS_NAME,
                "user-username", SentinelRuleConfig.RES_DEP_USER_USERNAME
        ));
        m.put("sentinel", sentinel);

        // ── Caffeine 本地缓存（活动信息热点隔离） ──
        CacheStats s = hotActivityLocalCache.stats();
        Map<String, Object> caffeine = new LinkedHashMap<>();
        caffeine.put("estimatedSize", hotActivityLocalCache.asCache().estimatedSize());
        caffeine.put("hitCount", s.hitCount());
        caffeine.put("missCount", s.missCount());
        caffeine.put("hitRate", String.format("%.1f%%", s.hitRate() * 100));
        caffeine.put("evictionCount", s.evictionCount());
        m.put("caffeine", caffeine);

        // ── Redis 实时库存（传 activityId 才查） ──
        if (activityId != null) {
            int redisStock = seckillOrderService.getRedisStock(activityId);
            m.put("redisStock", redisStock);
        }

        // ── Sentinel 控制台地址 ──
        m.put("sentinelDashboard", "http://localhost:8858");

        return Result.ok(m);
    }
}