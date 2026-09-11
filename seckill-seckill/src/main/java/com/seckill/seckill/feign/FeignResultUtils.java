package com.seckill.seckill.feign;

import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.common.result.Result;

/**
 * Feign 返回解包工具。
 *
 * 跨服务调用约定：业务失败一律 HTTP 200 + Result.code!=0（GlobalExceptionHandler 保证），
 * 所以 Feign 拿到响应后不抛异常，由本工具统一解包：
 * code!=0 时把依赖服务的错误码【原样】包成 BizException 抛出——
 * 例如 goods 抛 2004（库存不足），seckill 侧抛出的 BizException.code 也是 2004，
 * 前端/消费端的错误分类逻辑完全不用改，错误码语义跨服务透传。
 */
public final class FeignResultUtils {

    private FeignResultUtils() {
    }

    public static <T> T unwrap(Result<T> result) {
        if (result == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR.getCode(), "依赖服务返回为空");
        }
        if (result.getCode() != ErrorCode.SUCCESS.getCode()) {
            throw new BizException(result.getCode(), result.getMessage());
        }
        return result.getData();
    }
}
