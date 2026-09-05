package com.seckill.common.result;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    SUCCESS(0, "success"),

    PARAM_INVALID(400, "参数不合法"),

    USERNAME_EXISTS(1001, "用户名已存在"),
    USER_NOT_FOUND(1002, "用户不存在"),
    PASSWORD_ERROR(1003, "密码错误"),

    GOODS_NOT_FOUND(2001, "商品不存在"),
    ACTIVITY_NOT_FOUND(2002, "活动不存在"),
    ACTIVITY_TIME_INVALID(2003, "活动时间不合法"),
    STOCK_NOT_ENOUGH(2004, "库存不足"),

    INTERNAL_ERROR(500, "系统繁忙，请稍后重试");

    private final int code;
    private final String message;
}
