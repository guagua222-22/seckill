package com.seckill.seckill.metrics;

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotEntryCallback;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowException;
import com.alibaba.csp.sentinel.slots.statistic.StatisticSlotCallbackRegistry;
import com.seckill.seckill.config.SentinelRuleConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** M7：直接监听 Sentinel 的真实拒绝事件，业务返回 HTTP 200 也不会漏计。 */
@Component
public class SentinelMetrics implements SmartInitializingSingleton, DisposableBean,
        ProcessorSlotEntryCallback<DefaultNode> {
    private static final String CALLBACK = "seckill-micrometer";
    private final Map<String, Map<String, Counter>> counters = new HashMap<>();

    public SentinelMetrics(MeterRegistry registry) {
        // 固定资源、固定原因：绝不把 activityId、userId、URL 原值放进标签。
        for (String resource : new String[]{SentinelRuleConfig.RES_CREATE_ORDER,
                SentinelRuleConfig.RES_DEP_GOODS_ACTIVITY, SentinelRuleConfig.RES_DEP_GOODS_NAME,
                SentinelRuleConfig.RES_DEP_USER_USERNAME}) {
            Map<String, Counter> reasons = new HashMap<>();
            for (String reason : new String[]{"flow", "hotspot", "degrade", "other"}) {
                reasons.put(reason, Counter.builder("seckill.sentinel.blocked")
                        .description("Sentinel rejected entries, not HTTP errors")
                        .tags("resource", resource, "reason", reason).register(registry));
            }
            counters.put(resource, reasons);
        }
    }

    @Override
    public void afterSingletonsInstantiated() {
        // 保留 M6 的初始化时序：在 SCA 读取控制台地址之后才碰 Sentinel 静态注册表。
        StatisticSlotCallbackRegistry.addEntryCallback(CALLBACK, this);
    }

    @Override
    public void onPass(Context context, ResourceWrapper resource, DefaultNode node, int count, Object... args) {
    }

    @Override
    public void onBlocked(BlockException ex, Context context, ResourceWrapper resource,
                          DefaultNode node, int count, Object... args) {
        Map<String, Counter> reasons = counters.get(resource.getName());
        if (reasons == null) return;
        String reason = ex instanceof ParamFlowException ? "hotspot"
                : ex instanceof DegradeException ? "degrade"
                : ex instanceof FlowException ? "flow" : "other";
        reasons.get(reason).increment(count);
    }

    @Override
    public void destroy() {
        StatisticSlotCallbackRegistry.removeEntryCallback(CALLBACK);
    }
}
