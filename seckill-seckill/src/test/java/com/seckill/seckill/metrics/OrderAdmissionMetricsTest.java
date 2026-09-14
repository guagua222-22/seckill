package com.seckill.seckill.metrics;

import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 同一个请求仅记一次结果；保留原异常供全局异常处理器转换。 */
class OrderAdmissionMetricsTest {
    @Test
    void separatesAcceptedBusinessRejectedAndUnexpectedError() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OrderAdmissionMetrics metrics = new OrderAdmissionMetrics(registry);
        metrics.record(() -> {});
        BizException rejected = new BizException(ErrorCode.STOCK_NOT_ENOUGH);
        assertSame(rejected, assertThrows(BizException.class, () -> metrics.record(() -> { throw rejected; })));
        IllegalStateException error = new IllegalStateException("db unavailable");
        assertSame(error, assertThrows(IllegalStateException.class, () -> metrics.record(() -> { throw error; })));
        assertEquals(1, registry.get("seckill.order.admission").tag("code", "0").counter().count());
        assertEquals(1, registry.get("seckill.order.admission").tag("code", "2004").counter().count());
        assertEquals(1, registry.get("seckill.order.admission").tag("code", "500").counter().count());
        assertEquals(3, registry.get("seckill.order.admission").counters().stream().mapToDouble(c -> c.count()).sum());
    }
}
