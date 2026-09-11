package com.seckill.goods.service;

import com.seckill.common.api.goods.DeductStockRequest;
import com.seckill.common.api.goods.RollbackStockRequest;

/**
 * 库存扣减/回滚服务（M5 拆分新增，供 seckill-service 的 Feign 调用）。
 *
 * 幂等设计是核心：requestId 是下单请求的幂等键，扣减与回滚都以它为唯一约束，
 * 保证 Feign 超时重试、MQ 消息重投、对账任务无条件补偿都不会重复生效。
 */
public interface StockOperationService {

    /**
     * 条件扣减库存（available_stock > 0 才扣，防超卖）。
     * 同一 requestId 重复调用直接返回成功（幂等），不会二次扣减。
     */
    void deduct(DeductStockRequest request);

    /**
     * 回滚扣减（插单失败补偿/对账兜底）。
     * 幂等语义：同一 requestId 只回滚一次；没有扣减流水也返回成功，
     * 因此对账任务可以无条件调用。
     */
    void rollback(RollbackStockRequest request);
}
