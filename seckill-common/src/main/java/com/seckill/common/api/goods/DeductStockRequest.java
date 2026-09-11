package com.seckill.common.api.goods;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 扣减库存请求（seckill-service → goods-service 的 Feign 入参）。
 * requestId 是幂等键：与下单请求的 requestId 一致，
 * goods 侧用它在 t_stock_operation 唯一索引上做幂等——Feign 超时重试
 * 不会造成重复扣减。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeductStockRequest {

    /** 幂等键（= 下单 requestId） */
    private String requestId;

    private Long goodsId;

    private Long activityId;

    /** 扣减数量（固定 1） */
    private Integer amount;
}
