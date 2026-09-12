package com.seckill.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.exception.SentinelGatewayBlockExceptionHandler;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.http.codec.ServerCodecConfigurer;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 网关层 Sentinel 限流（M6）。
 *
 * 为什么网关还要再限一道：服务内的 FlowRule 保护的是"单个服务实例"，
 * 而网关限流保护的是"整个入口带宽"——流量大到网关/下游连接池先撑不住时，
 * 服务内规则还来不及生效。两层阈值不同、职责不同（入口总闸 vs 实例自保）。
 *
 * 规则资源名 = 路由 id（GatewayFlowRule 默认按 route 维度统计），
 * 被拒绝时返回与业务同构的 JSON（code=429），前端验证台能直接读懂。
 */
@Configuration
public class SentinelGatewayConfig {

    /** 秒杀路由的入口 QPS 上限：比服务内总闸（2000×实例数）略高，只挡异常洪峰 */
    @Value("${gateway.sentinel.seckill-route-qps:5000}")
    private long seckillRouteQps;

    @PostConstruct
    public void init() {
        GatewayFlowRule seckillRoute = new GatewayFlowRule("seckill-service");
        seckillRoute.setCount(seckillRouteQps);
        seckillRoute.setIntervalSec(1);
        GatewayRuleManager.loadRules(Set.of(seckillRoute));

        // 自定义被限流后的响应：默认返回空 body 的 429，前端无法区分"限流"和"网关坏了"
        GatewayCallbackManager.setBlockHandler(this::blockedResponse);
    }

    /** 限流响应与业务 Result 同构（code/message/data），验证台日志能直接显示原因 */
    private Mono<ServerResponse> blockedResponse(ServerWebExchange exchange, Throwable t) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 429);
        body.put("message", "请求太火爆，请稍后再试");
        body.put("data", null);
        return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body);
    }

    /** 限流过滤器必须排在最前：被拒的请求不该再消耗路由/转发资源 */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SentinelGatewayFilter sentinelGatewayFilter() {
        return new SentinelGatewayFilter();
    }

    /** BlockException 在 webflux 里要走专用异常处理器，否则变成 500 */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SentinelGatewayBlockExceptionHandler sentinelGatewayBlockExceptionHandler(
            ObjectProvider<List<ViewResolver>> viewResolvers, ServerCodecConfigurer serverCodecs) {
        return new SentinelGatewayBlockExceptionHandler(
                viewResolvers.getIfAvailable(List::of), serverCodecs);
    }
}
