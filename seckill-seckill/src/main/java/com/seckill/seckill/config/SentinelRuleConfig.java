package com.seckill.seckill.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Sentinel 规则代码化（M6，控制台只读不改规则）。
 *
 * 为什么规则写代码而不是控制台/配置中心：
 * 规则是和容量一起演进的"工程常量"（压测得出阈值后改代码走评审），
 * 放控制台容易被随手改坏且无法单测；控制台只承担"看实时 QPS/熔断状态"的职责。
 *
 * 为什么用 SmartInitializingSingleton 而不是 @PostConstruct：
 * spring.cloud.sentinel.transport.dashboard 这个 yml 配置，是靠 SCA 的
 * SentinelAutoConfiguration.init()（也是 @PostConstruct）搬进 csp.sentinel.dashboard.server
 * 系统属性的；而自动配置 Bean 一定排在用户 Bean 之后实例化。
 * 所以这里若用 @PostConstruct，就会抢在 SCA 之前把 Sentinel 核心类初始化掉——
 * 心跳发送器的控制台地址列表在构造时就定死了，读到的是空列表，
 * 于是这个 JVM 整个生命周期都不会向控制台发心跳（控制台里永远看不到本服务）。
 * afterSingletonsInstantiated() 在"所有单例的 @PostConstruct 都跑完"之后、
 * "Web 容器开始接收流量"之前触发，两个条件正好都满足。
 *
 * 三类规则各挡一件事：
 * 1. FlowRule（QPS 流控）：下单入口总闸门，保护 DB/MQ 不被瞬时流量打穿；
 * 2. ParamFlowRule（热点参数限流）：按 activityId 分别计数，单个热点活动的流量
 *    不会吃光总配额（经典面试题"热点商品隔离"的限流侧答案，缓存侧见 goods 的 Caffeine）；
 * 3. DegradeRule（熔断降级）：依赖服务（goods/user）异常比例超阈值就熔断一段时间，
 *    熔断期间快速失败返回 503，避免调用方线程被慢依赖拖死（雪崩防线）。
 */
@Configuration
public class SentinelRuleConfig implements SmartInitializingSingleton {

    /** 下单入口资源名：QPS 流控 + 热点参数限流都挂在它上面 */
    public static final String RES_CREATE_ORDER = "seckill:createOrder";
    /** 依赖资源名：熔断规则按语义命名，SentinelGuard 埋点时用同一常量 */
    public static final String RES_DEP_GOODS_ACTIVITY = "dep:goods:activity";
    public static final String RES_DEP_GOODS_NAME = "dep:goods:goodsName";
    public static final String RES_DEP_USER_USERNAME = "dep:user:username";

    /** 下单总 QPS 阈值：M3 压测单机闸门约 3000 QPS，留余量取 2000 作保护线 */
    @Value("${seckill.sentinel.order-qps:2000}")
    private long orderQps;

    /** 单个活动的 QPS 阈值：热点活动单独限流，不挤占其他活动的配额 */
    @Value("${seckill.sentinel.hot-activity-qps:1000}")
    private long hotActivityQps;

    /** 熔断触发条件：统计窗口内异常比例超过该值就熔断 */
    @Value("${seckill.sentinel.degrade-exception-ratio:0.5}")
    private double degradeExceptionRatio;

    /** 熔断持续秒数：打开后这段时间内直接快速失败，期满进入半开试探 */
    @Value("${seckill.sentinel.degrade-window-seconds:10}")
    private int degradeWindowSeconds;

    @Override
    public void afterSingletonsInstantiated() {
        FlowRule flow = new FlowRule(RES_CREATE_ORDER);
        flow.setGrade(RuleConstant.FLOW_GRADE_QPS);
        flow.setCount(orderQps);
        FlowRuleManager.loadRules(List.of(flow));

        // paramIdx=0 对应 SentinelGuard.call 传入的第一个热点参数（activityId）
        ParamFlowRule hot = new ParamFlowRule(RES_CREATE_ORDER);
        hot.setParamIdx(0);
        hot.setGrade(RuleConstant.FLOW_GRADE_QPS);
        hot.setCount(hotActivityQps);
        ParamFlowRuleManager.loadRules(List.of(hot));

        DegradeRuleManager.loadRules(List.of(
                degradeRule(RES_DEP_GOODS_ACTIVITY),
                degradeRule(RES_DEP_GOODS_NAME),
                degradeRule(RES_DEP_USER_USERNAME)));
    }

    /** 异常比例熔断：窗口内至少 5 个请求才统计，避免小样本误熔断 */
    private DegradeRule degradeRule(String resource) {
        DegradeRule rule = new DegradeRule(resource);
        rule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
        rule.setCount(degradeExceptionRatio);
        rule.setTimeWindow(degradeWindowSeconds);
        rule.setMinRequestAmount(5);
        rule.setStatIntervalMs(10_000);
        return rule;
    }
}
