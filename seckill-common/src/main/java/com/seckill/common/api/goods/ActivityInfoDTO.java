package com.seckill.common.api.goods;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 活动信息跨服务契约 DTO。
 * 用途：goods-service 提供给 seckill-service 的 Feign 返回体；
 * seckill-service 在 Redis 活动缓存 miss 时用它兜底查库，
 * 也用它承载 MQ 消息里的活动快照。
 * 字段与 SeckillActivity 实体一一对应，但无 MyBatis-Plus 注解，与表结构解耦。
 */
@Data
public class ActivityInfoDTO {

    private Long id;

    private String activityName;

    private Long goodsId;

    /**
     * 商品名快照：goods 预热时一并写进 Redis 活动缓存，seckill 下单直接取用，
     * 省掉每单一次 Feign 回查商品名（缓存里没有该字段时才降级回查）。
     */
    private String goodsName;

    /** 秒杀价（低于日常价） */
    private BigDecimal seckillPrice;

    /** 活动库存：创建活动时写入库存表并预热到 Redis */
    private Integer totalStock;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 0 未开始 / 1 进行中 / 2 已结束（仅展示用，下单校验以时间窗为准） */
    private Integer status;

    /** 是否热点商品：1 时走本地缓存隔离（M6） */
    private Integer isHot;
}
