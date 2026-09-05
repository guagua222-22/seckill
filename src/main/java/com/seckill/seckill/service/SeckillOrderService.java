package com.seckill.seckill.service;

import com.seckill.seckill.dto.SeckillOrderDTO;

public interface SeckillOrderService {

    /**
     * DB 版同步下单：时间窗校验 → 一人一单预检 → 条件更新扣库存 → 落订单。
     * M3 起内部升级为 Redis Lua 预扣 + DB 落单双保险。
     *
     * @return 订单ID
     */
    Long createOrder(SeckillOrderDTO dto);

    /**
     * 查询 Redis 中某活动的实时剩余库存（前端展示用）。
     *
     * @return 剩余库存数；-1 表示未预热（key 不存在）
     */
    Integer getRedisStock(Long activityId);
}
