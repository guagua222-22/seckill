package com.seckill.seckill.feign;

import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import feign.Response;
import feign.codec.ErrorDecoder;
import lombok.extern.slf4j.Slf4j;

/**
 * Feign 错误解码器：只处理"基础设施失败"（连接拒绝/超时/非 2xx），
 * 业务失败（HTTP 200 + code!=0）不走这里——那部分由 FeignResultUtils 解包处理。
 *
 * 为什么转成 BizException(500) 而不是让 FeignException 抛出去：
 * 消费端 SeckillOrderConsumerService 的 catch(BizException) 按错误码分类处理——
 * ALREADY_ORDERED/STOCK_NOT_ENOUGH 走终态，其余 rethrow 触发 MQ 重试。
 * 依赖服务暂时不可用必须归入"其余"，让 MQ 重试兜底，而不是误判成业务终态。
 */
@Slf4j
public class SeckillFeignErrorDecoder implements ErrorDecoder {

    @Override
    public Exception decode(String methodKey, Response response) {
        log.warn("Feign 调用基础设施失败: method={}, status={}", methodKey, response.status());
        return new BizException(ErrorCode.INTERNAL_ERROR.getCode(),
                "依赖服务暂不可用，请稍后重试");
    }
}
