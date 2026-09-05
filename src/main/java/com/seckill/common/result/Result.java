package com.seckill.common.result;

import lombok.Data;

/**
 * 统一 API 返回结构。
 * 所有接口都返回这个结构：code=0 表示成功，非 0 表示业务失败（见 {@link ErrorCode}），
 * data 是真正的业务数据。前端/调用方只判断 code 即可，不用解析各种异常响应格式。
 */
@Data
public class Result<T> {

    /** 业务码：0 成功，其余见 ErrorCode */
    private int code;

    /** 提示信息：成功时为 success，失败时为具体原因 */
    private String message;

    /** 业务数据：可能为 null */
    private T data;

    /** 成功（无数据） */
    public static <T> Result<T> ok() {
        return ok(null);
    }

    /** 成功（携带数据） */
    public static <T> Result<T> ok(T data) {
        Result<T> result = new Result<>();
        result.code = ErrorCode.SUCCESS.getCode();
        result.message = ErrorCode.SUCCESS.getMessage();
        result.data = data;
        return result;
    }

    /** 失败：按错误码枚举返回 */
    public static <T> Result<T> fail(ErrorCode errorCode) {
        Result<T> result = new Result<>();
        result.code = errorCode.getCode();
        result.message = errorCode.getMessage();
        return result;
    }

    /** 失败：自定义码和消息（用于动态文案场景） */
    public static <T> Result<T> fail(int code, String message) {
        Result<T> result = new Result<>();
        result.code = code;
        result.message = message;
        return result;
    }
}

