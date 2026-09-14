package com.seckill.seckill.metrics;

import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** 请求准入结果不等于异步订单最终结果；HTTP 200 内的业务失败在这里单独可见。 */
@Component
public class OrderAdmissionMetrics {
    private final Map<Integer, Counter> counters = new HashMap<>();

    public OrderAdmissionMetrics(MeterRegistry registry) {
        for (ErrorCode code : ErrorCode.values()) {
            counters.put(code.getCode(), registry.counter("seckill.order.admission",
                    "code", Integer.toString(code.getCode())));
        }
    }

    public void record(Runnable action) {
        int code = ErrorCode.INTERNAL_ERROR.getCode();
        try {
            action.run();
            code = ErrorCode.SUCCESS.getCode();
        } catch (BizException e) {
            code = counters.containsKey(e.getCode()) ? e.getCode() : ErrorCode.INTERNAL_ERROR.getCode();
            throw e;
        } finally {
            counters.get(code).increment();
        }
    }
}
