package com.seckill.common.exception;

import com.seckill.common.result.ErrorCode;
import com.seckill.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：把各种异常统一转成 Result JSON，接口永远返回 200 + 业务码。
 *
 * 分类原则（面试常考）：
 * - 客户端问题（参数错、JSON 格式错）→ 400，不该记 error 日志
 * - 业务规则不满足 → 对应业务码，记 warn 日志
 * - 未预期异常 → 500 兜底，必须记 error 日志（这是告警依据）
 *
 * 微服务拆分后每个服务都要有这份处理器（放 common 模块共享），
 * 保证所有服务对外暴露完全一致的错误响应格式。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** JSR-303 参数校验失败（@Valid 注解触发） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError == null ? ErrorCode.PARAM_INVALID.getMessage() : fieldError.getDefaultMessage();
        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), message);
    }

    /** 请求体解析失败（如 JSON 格式错误、编码错误）——客户端问题，返回 400 而非 500 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "请求体格式错误");
    }

    /** 数据库唯一键冲突（如并发重复下单撞 uk_user_activity） */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKeyException(DuplicateKeyException e) {
        log.warn("唯一键冲突: {}", e.getMessage());
        return Result.fail(ErrorCode.INTERNAL_ERROR.getCode(), "数据已存在，请勿重复提交");
    }

    /** 兜底：任何未预期的异常都转成 500，不让堆栈泄露给调用方 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.fail(ErrorCode.INTERNAL_ERROR);
    }
}
