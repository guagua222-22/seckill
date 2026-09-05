package com.seckill.seckill.service;

import com.seckill.seckill.dto.SeckillOrderDTO;

public interface SeckillOrderService {

    /**
     * DB 版同步下单：时间窗校验 → 一人一单预检 → 条件更新扣库存 → 落订单。
     *
     * @return 订单ID
     */
    Long createOrder(SeckillOrderDTO dto);
}
