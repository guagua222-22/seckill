package com.seckill.seckill.controller;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.seckill.config.SentinelRuleConfig;
import com.seckill.seckill.dto.SeckillOrderDTO;
import com.seckill.seckill.service.SeckillOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 下单入口限流单测：验证 Sentinel 拒绝时对外是 429 语义码，
 * 且被拒请求不会打进下单链路（保护 DB/MQ 的关键断言）。
 */
@ExtendWith(MockitoExtension.class)
class SeckillControllerTest {

    @Mock
    private SeckillOrderService seckillOrderService;

    private SeckillController controller;

    @BeforeEach
    void setUp() {
        controller = new SeckillController(seckillOrderService,
                new com.seckill.seckill.metrics.OrderAdmissionMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    @AfterEach
    void clearRules() {
        FlowRuleManager.loadRules(List.of());
    }

    private SeckillOrderDTO dto() {
        SeckillOrderDTO dto = new SeckillOrderDTO();
        dto.setUserId(1L);
        dto.setActivityId(100L);
        dto.setRequestId("req-1");
        return dto;
    }

    @Test
    @DisplayName("无限流规则：正常放行进入下单链路")
    void passThrough() {
        controller.order(dto());
        verify(seckillOrderService).createOrder(dto());
    }

    @Test
    @DisplayName("超 QPS 阈值：抛 429 语义码且不打进下单链路")
    void rateLimited() {
        FlowRule rule = new FlowRule(SentinelRuleConfig.RES_CREATE_ORDER);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(0);
        FlowRuleManager.loadRules(List.of(rule));

        BizException e = assertThrows(BizException.class, () -> controller.order(dto()));
        assertEquals(ErrorCode.RATE_LIMITED.getCode(), e.getCode());
        verify(seckillOrderService, never()).createOrder(org.mockito.ArgumentMatchers.any());
    }
}
