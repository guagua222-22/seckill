package com.seckill.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.exception.SentinelGatewayBlockExceptionHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

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
 *
 * 为什么用 SmartInitializingSingleton 而不是 @PostConstruct：
 * yml 里的 spring.cloud.sentinel.transport.dashboard 是靠 SCA 自动配置的 @PostConstruct
 * 搬进 csp.sentinel.dashboard.server 系统属性的，而自动配置 Bean 排在用户 Bean 之后实例化。
 * 在这里用 @PostConstruct 就有抢跑风险——一旦先把 Sentinel 核心类初始化了，
 * 心跳发送器会读到空的控制台地址列表并且终身不再重读，控制台里就永远看不到这个服务
 * （seckill-service 已经踩过，症状是控制台只有网关没有业务服务，且客户端日志里
 * 只有一行 WARNING: Dashboard server address not configured or not available）。
 * afterSingletonsInstantiated() 在所有单例的 @PostConstruct 之后、Web 容器接收流量之前触发。
 */
@Configuration
public class SentinelGatewayConfig implements SmartInitializingSingleton {

    private final Counter blocked;

    public SentinelGatewayConfig(MeterRegistry registry) {
        // 当前唯一网关规则是 seckill-service。只使用固定标签，禁止请求 URL/ID 入标签。
        blocked = registry.counter("seckill.gateway.blocked", "route", "seckill-service");
    }

    /** 秒杀路由的入口 QPS 上限：比服务内总闸（2000×实例数）略高，只挡异常洪峰 */
    @Value("${gateway.sentinel.seckill-route-qps:5000}")
    private long seckillRouteQps;

    @Override
    public void afterSingletonsInstantiated() {
        GatewayFlowRule seckillRoute = new GatewayFlowRule("seckill-service");
        seckillRoute.setCount(seckillRouteQps);
        seckillRoute.setIntervalSec(1);
        GatewayRuleManager.loadRules(Set.of(seckillRoute));

        // 自定义被限流后的响应：默认返回空 body 的 429，前端无法区分"限流"和"网关坏了"
        GatewayCallbackManager.setBlockHandler(this::blockedResponse);
    }

    /** 限流响应与业务 Result 同构（code/message/data），验证台日志能直接显示原因 */
    private Mono<ServerResponse> blockedResponse(ServerWebExchange exchange, Throwable t) {
        blocked.increment();
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
