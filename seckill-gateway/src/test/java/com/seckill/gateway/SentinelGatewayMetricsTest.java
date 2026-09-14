package com.seckill.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证真实注册的网关拒绝回调仍返回 429，且每次只累计一次。 */
class SentinelGatewayMetricsTest {
    @Test
    void blockedCallbackCountsAndPreservesStatus() {
        var previous = GatewayCallbackManager.getBlockHandler();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            SentinelGatewayConfig config = new SentinelGatewayConfig(registry);
            ReflectionTestUtils.setField(config, "seckillRouteQps", 5000L);
            config.afterSingletonsInstantiated();
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/seckill/order").build());
            var response = GatewayCallbackManager.getBlockHandler().handleRequest(exchange, new RuntimeException()).block();
            assertEquals(429, response.statusCode().value());
            assertEquals(1, registry.get("seckill.gateway.blocked").counter().count());
        } finally {
            GatewayRuleManager.loadRules(Set.of());
            GatewayCallbackManager.setBlockHandler(previous);
            registry.close();
        }
    }
}
