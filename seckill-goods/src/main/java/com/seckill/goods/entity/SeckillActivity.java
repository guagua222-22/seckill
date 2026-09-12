package com.seckill.goods.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀活动实体，映射 t_seckill_activity 表。
 * status 字段（0 未开始/1 进行中/2 已结束）只作展示用途；
 * 下单校验一律以 start_time/end_time 时间窗为准（status 可能因任务延迟而不准）。
 */
@Data
@TableName("t_seckill_activity")
public class SeckillActivity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String activityName;

    private Long goodsId;

    /** 商品名快照(反范式)：配置行语义，商品改名时同步刷新，看活动表不必回 t_goods 对照 */
    private String goodsName;

    /** 秒杀价（低于日常价） */
    private BigDecimal seckillPrice;

    /** 活动库存：创建活动时写入 t_stock 并预热到 Redis */
    private Integer totalStock;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 0 未开始 / 1 进行中 / 2 已结束（展示用） */
    private Integer status;

    /** 是否热点商品：1 时 M6 走 Caffeine 本地缓存隔离 */
    private Integer isHot;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
