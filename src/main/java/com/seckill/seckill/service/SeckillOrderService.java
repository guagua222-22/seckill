package com.seckill.seckill.service;

import com.seckill.seckill.dto.SeckillOrderDTO;

public interface SeckillOrderService {

    /**
     * 秒杀下单（M4：异步排队模式）。
     * 入口只做校验 + Lua 预扣 + 本地事务落"流水+消息"，随后立即返回"排队中"，
     * 真正的落单由 RocketMQ 消费端异步完成（前端轮询 /api/order/query 拿结果）。
     * Redis 故障时降级 DB 同步直写（M2 链路）。
     */
    void createOrder(SeckillOrderDTO dto);

    /**
     * 查询 Redis 中某活动的实时剩余库存（前端展示用）。
     *
     * @return 剩余库存数；-1 表示未预热（key 不存在）
     */
    Integer getRedisStock(Long activityId);
}
