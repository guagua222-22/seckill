package com.seckill.seckill.metrics;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.seckill.config.SentinelRuleConfig;
import com.seckill.seckill.sentinel.SentinelGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** 走真实 Sentinel slot 链，防止回调未注册而看板一直为零。 */
class SentinelMetricsTest {
    @Test
    void realSentinelRejectionCountedOnceAndBusinessRejectionNotCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SentinelMetrics metrics = new SentinelMetrics(registry);
        String resource = SentinelRuleConfig.RES_CREATE_ORDER;
        try {
            metrics.afterSingletonsInstantiated();
            assertThrows(BizException.class, () -> SentinelGuard.call(resource, ErrorCode.RATE_LIMITED,
                    () -> { throw new BizException(ErrorCode.STOCK_NOT_ENOUGH); }));
            assertEquals(0, registry.get("seckill.sentinel.blocked").tag("resource", resource)
                    .tag("reason", "flow").counter().count());
            FlowRule rule = new FlowRule(resource);
            rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
            rule.setCount(0);
            FlowRuleManager.loadRules(List.of(rule));
            AtomicBoolean entered = new AtomicBoolean();
            assertThrows(BizException.class, () -> SentinelGuard.call(resource, ErrorCode.RATE_LIMITED,
                    () -> { entered.set(true); return null; }));
            assertFalse(entered.get());
            assertEquals(1, registry.get("seckill.sentinel.blocked").tag("resource", resource)
                    .tag("reason", "flow").counter().count());
        } finally {
            FlowRuleManager.loadRules(List.of());
            metrics.destroy();
            registry.close();
        }
    }
}
