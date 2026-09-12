package com.seckill.seckill.sentinel;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;

import java.util.function.Supplier;

/**
 * Sentinel 手动埋点工具（M6）。
 *
 * 为什么不用 @SentinelResource 注解：
 * 1. 热点参数限流要拿 DTO 里的 activityId 当热点键，注解只能按方法形参下标取，取不到嵌套字段；
 *    手动 SphU.entry(resource, EntryType.IN, 1, args) 可以把任意值作为热点参数传进去。
 * 2. 依赖调用的熔断资源名要可控（dep:goods:activity 这种语义名），
 *    Feign 适配器自动生成的资源名带 URL 模板，配规则时不可读也不好测。
 *
 * 熔断统计口径（面试考点）：只有"依赖真的坏了"（网络/5xx 等运行时异常）才 Tracer.traceEntry 记账；
 * 业务失败（如活动不存在）抛的是 BizException，不记账——否则正常业务拒绝会把熔断器误触发。
 */
public final class SentinelGuard {

    private SentinelGuard() {
    }

    /**
     * 在 Sentinel 保护下执行 action。
     *
     * @param resource  资源名（规则就配在这个名字上）
     * @param blockedAs 被限流/熔断拒绝时抛的错误码：入口限流用 RATE_LIMITED，依赖降级用 SERVICE_DEGRADED
     * @param hotArgs   热点参数（可选）：传给热点参数限流规则做"按值统计"，如 activityId
     */
    public static <T> T call(String resource, ErrorCode blockedAs, Supplier<T> action, Object... hotArgs) {
        Entry entry = null;
        try {
            entry = SphU.entry(resource, EntryType.IN, 1, hotArgs);
            return action.get();
        } catch (BlockException e) {
            // 被规则拒绝：快速失败，不排队不重试，把容量留给能成功的请求
            throw new BizException(blockedAs);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 运行时异常记入熔断统计（异常比例/异常数规则的依据）
            if (entry != null) {
                Tracer.traceEntry(e, entry);
            }
            throw e;
        } finally {
            if (entry != null) {
                entry.exit(1, hotArgs);
            }
        }
    }
}
