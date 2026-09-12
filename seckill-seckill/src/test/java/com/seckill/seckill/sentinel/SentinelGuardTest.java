package com.seckill.seckill.sentinel;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SentinelGuard 单测：限流拒绝、熔断统计口径、异常透传三条语义。
 * 规则直接在测试里加载/清空，不依赖 Spring 上下文。
 */
class SentinelGuardTest {

    private static final String RES = "test:guard";

    @AfterEach
    void clearRules() {
        // 规则是全局静态状态，不清空会污染其它测试类
        FlowRuleManager.loadRules(List.of());
    }

    private void blockAll() {
        FlowRule rule = new FlowRule(RES);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(0);
        FlowRuleManager.loadRules(List.of(rule));
    }

    @Test
    @DisplayName("无规则：正常放行并返回业务结果")
    void passThrough() {
        String result = SentinelGuard.call(RES, ErrorCode.RATE_LIMITED, () -> "ok");
        assertEquals("ok", result);
    }

    @Test
    @DisplayName("被限流：按调用方指定的错误码快速失败")
    void blockedUsesGivenErrorCode() {
        blockAll();
        BizException e = assertThrows(BizException.class,
                () -> SentinelGuard.call(RES, ErrorCode.RATE_LIMITED, () -> "ok"));
        assertEquals(ErrorCode.RATE_LIMITED.getCode(), e.getCode());
    }

    @Test
    @DisplayName("被限流：业务动作一次都不执行（快速失败不排队）")
    void blockedSkipsAction() {
        blockAll();
        AtomicInteger runs = new AtomicInteger();
        assertThrows(BizException.class,
                () -> SentinelGuard.call(RES, ErrorCode.SERVICE_DEGRADED, () -> {
                    runs.incrementAndGet();
                    return "ok";
                }));
        assertEquals(0, runs.get());
    }

    @Test
    @DisplayName("业务异常原样透传：不被改成降级码，也不记熔断账")
    void bizExceptionPropagates() {
        BizException biz = new BizException(ErrorCode.STOCK_NOT_ENOUGH);
        BizException e = assertThrows(BizException.class,
                () -> SentinelGuard.call(RES, ErrorCode.SERVICE_DEGRADED, () -> {
                    throw biz;
                }));
        assertSame(biz, e);
    }

    @Test
    @DisplayName("运行时异常原样透传：交给上层决定重试/降级语义")
    void runtimeExceptionPropagates() {
        IllegalStateException ise = new IllegalStateException("dep down");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> SentinelGuard.call(RES, ErrorCode.SERVICE_DEGRADED, () -> {
                    throw ise;
                }));
        assertSame(ise, e);
    }
}
