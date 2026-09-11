package com.seckill.common.api.goods;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 回滚库存请求（seckill-service → goods-service 的 Feign 入参）。
 * 幂等语义：同一 requestId 只回滚一次（t_stock_operation 状态推进保证），
 * 即使没有扣减流水也返回成功——对账任务可以无条件调用。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RollbackStockRequest {

    /** 幂等键（= 下单 requestId，与扣减时一致） */
    private String requestId;
}
