package com.seckill.common.result;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 业务错误码约定。
 * 按号段划分问题域，排查问题时看码就知道是哪一类：
 * <pre>
 * 0       成功
 * 400     参数类错误（校验失败、请求体格式错误）
 * 429     限流（Sentinel QPS/热点参数规则拒绝）
 * 503     熔断降级（依赖服务不可用，快速失败）
 * 100x    用户域（注册/登录）
 * 200x    商品/活动域（含秒杀时间窗、库存）
 * 300x    订单域（查单、重复下单）
 * 500     系统兜底（未预期异常）
 * </pre>
 * 微服务拆分后错误码全局唯一对齐：goods/user 服务抛出的码，
 * 经 Feign 的 Result 原样透传到 seckill-service 再转 BizException 抛出。
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    SUCCESS(0, "success"),

    PARAM_INVALID(400, "参数不合法"),

    /** M6 Sentinel：QPS/热点参数规则拒绝，前端提示稍后再试 */
    RATE_LIMITED(429, "请求太火爆，请稍后再试"),
    /** M6 Sentinel：依赖服务熔断打开期间快速失败，不拖住调用方线程 */
    SERVICE_DEGRADED(503, "依赖服务降级中，请稍后重试"),

    USERNAME_EXISTS(1001, "用户名已存在"),
    USER_NOT_FOUND(1002, "用户不存在"),
    PASSWORD_ERROR(1003, "密码错误"),

    GOODS_NOT_FOUND(2001, "商品不存在"),
    ACTIVITY_NOT_FOUND(2002, "活动不存在"),
    ACTIVITY_TIME_INVALID(2003, "活动时间不合法"),
    STOCK_NOT_ENOUGH(2004, "库存不足"),
    ACTIVITY_NOT_STARTED(2005, "活动未开始"),
    ACTIVITY_ENDED(2006, "活动已结束"),

    ORDER_NOT_FOUND(3001, "订单不存在"),
    ALREADY_ORDERED(3002, "已抢过，请勿重复下单"),

    INTERNAL_ERROR(500, "系统繁忙，请稍后重试");

    private final int code;
    private final String message;
}
