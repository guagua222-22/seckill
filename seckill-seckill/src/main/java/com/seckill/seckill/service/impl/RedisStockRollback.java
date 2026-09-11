package com.seckill.seckill.service.impl;

import com.seckill.common.redis.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 预扣回滚器（消费端与对账任务共用）。
 * 回滚必须幂等：只有 SREM 确实移除了该用户（返回 1）才 INCR 库存，
 * 否则重复回滚会把库存多加，造成"幽灵库存"。
 */
@Component
@RequiredArgsConstructor
public class RedisStockRollback {

    private final StringRedisTemplate redis;

    public void rollback(Long userId, Long activityId) {
        Long removed = redis.opsForSet().remove(RedisKeys.userSet(activityId), String.valueOf(userId));
        if (removed != null && removed == 1) {
            redis.opsForValue().increment(RedisKeys.stock(activityId));
        }
    }
}
