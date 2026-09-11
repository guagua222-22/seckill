package com.seckill.common.exception;

import com.seckill.common.result.ErrorCode;
import lombok.Getter;

/**
 * 业务异常：业务规则不满足时抛出（如"用户名已存在"、"库存不足"）。
 * 携带错误码，由 {@link GlobalExceptionHandler} 统一捕获并转成 JSON 返回，
 * 业务代码里不需要到处 try-catch。
 * 微服务拆分后新增 (code, message) 构造器：Feign 调用方把依赖服务返回的
 * 错误码原样包成 BizException 抛出，错误码语义跨服务透传不丢失。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    /** 用依赖服务的原始错误码构造（Feign 透传场景） */
    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
