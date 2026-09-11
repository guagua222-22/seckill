package com.seckill.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 秒杀预扣流水，映射 t_seckill_record 表。
 * 职责：记录"谁在哪个活动用哪个请求ID预扣了库存"——
 * 它是 Redis 预扣与订单落库之间的账本，对账任务靠它找"预扣了但没下单"的缺口。
 * uk_request 唯一索引是请求级幂等的第一道物理防线。
 */
@Data
@TableName("t_seckill_record")
public class SeckillRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 与本地消息 messageId、订单 requestId 三处一致，是贯穿全链路的幂等键 */
    private String requestId;

    private Long userId;

    private Long activityId;

    /** 0 已预扣（排队中）/ 1 已下单 / 2 已回滚 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
