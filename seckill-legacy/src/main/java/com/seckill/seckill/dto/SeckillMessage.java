package com.seckill.seckill.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * MQ 消息体：消费端落单所需的全部信息。
 * 下单时把商品名/成交价快照进消息，消费端不需要再查商品库，
 * 也天然隔离了"下单后商品改价"的影响。
 */
@Data
public class SeckillMessage {

    /** 幂等键，与 t_seckill_record.requestId、t_order.request_id 一致 */
    private String requestId;

    private Long userId;

    private Long activityId;

    private Long goodsId;

    private String goodsName;

    private BigDecimal price;
}
