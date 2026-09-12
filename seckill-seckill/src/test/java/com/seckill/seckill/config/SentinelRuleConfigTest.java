package com.seckill.seckill.config;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SentinelRuleConfig 单测：确认三类规则真的注册进了 Sentinel 的规则管理器，
 * 且阈值来自配置（改 yml 不用改代码逻辑）。规则是全局静态状态，测完必须清空。
 */
class SentinelRuleConfigTest {

    private final SentinelRuleConfig config = new SentinelRuleConfig();

    @AfterEach
    void clearRules() {
        FlowRuleManager.loadRules(List.of());
        ParamFlowRuleManager.loadRules(List.of());
        DegradeRuleManager.loadRules(List.of());
    }

    private void loadWith(long orderQps, long hotQps) {
        ReflectionTestUtils.setField(config, "orderQps", orderQps);
        ReflectionTestUtils.setField(config, "hotActivityQps", hotQps);
        ReflectionTestUtils.setField(config, "degradeExceptionRatio", 0.5);
        ReflectionTestUtils.setField(config, "degradeWindowSeconds", 10);
        config.afterSingletonsInstantiated();
    }

    @Test
    @DisplayName("下单入口：QPS 流控规则按配置阈值注册")
    void flowRuleRegistered() {
        loadWith(2000, 1000);
        assertTrue(FlowRuleManager.getRules().stream().anyMatch(r ->
                SentinelRuleConfig.RES_CREATE_ORDER.equals(r.getResource()) && r.getCount() == 2000));
    }

    @Test
    @DisplayName("热点参数限流：按第 0 个参数（activityId）分别计数")
    void paramFlowRuleRegistered() {
        loadWith(2000, 1000);
        assertTrue(ParamFlowRuleManager.getRules().stream().anyMatch(r ->
                SentinelRuleConfig.RES_CREATE_ORDER.equals(r.getResource())
                        && r.getParamIdx() == 0 && r.getCount() == 1000));
    }

    @Test
    @DisplayName("熔断规则：三个依赖资源都注册为异常比例熔断")
    void degradeRulesRegistered() {
        loadWith(2000, 1000);
        var resources = DegradeRuleManager.getRules().stream()
                .map(r -> r.getResource()).toList();
        assertEquals(3, resources.size());
        assertTrue(resources.containsAll(List.of(
                SentinelRuleConfig.RES_DEP_GOODS_ACTIVITY,
                SentinelRuleConfig.RES_DEP_GOODS_NAME,
                SentinelRuleConfig.RES_DEP_USER_USERNAME)));
    }
}
