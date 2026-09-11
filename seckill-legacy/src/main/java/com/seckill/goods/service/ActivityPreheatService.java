package com.seckill.goods.service;

import com.seckill.goods.entity.SeckillActivity;

/**
 * 活动预热服务：把活动库存灌入 Redis，供 Lua 预扣使用。
 * 预热是下单能走 Redis 快闸门的前提——未预热的活动 Lua 会返回 -3（防穿透到 DB）。
 */
public interface ActivityPreheatService {

    /**
     * 预热单个活动：
     * 1. SETNX 写入库存计数（幂等，重复调用不会覆盖已扣减的库存）
     * 2. 写入活动信息缓存（时间窗、价格）
     */
    void preheat(SeckillActivity activity);

    /** 扫描"即将开始（5 分钟窗口内）或进行中"的活动并批量预热 */
    void preheatUpcoming();
}
