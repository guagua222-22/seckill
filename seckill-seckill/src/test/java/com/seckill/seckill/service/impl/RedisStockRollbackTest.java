package com.seckill.seckill.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 预扣回滚器单测：回滚必须幂等——只有 SREM 真移除了用户（返回 1）才 INCR 库存，
 * 重复回滚不产生幽灵库存。
 */
@ExtendWith(MockitoExtension.class)
class RedisStockRollbackTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private SetOperations<String, String> setOps;
    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private RedisStockRollback stockRollback;

    @Test
    @DisplayName("SREM 返回 1：真正移除，INCR 库存")
    void rollbackWhenRemoved() {
        when(redis.opsForSet()).thenReturn(setOps);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(setOps.remove(anyString(), anyString())).thenReturn(1L);

        stockRollback.rollback(1L, 100L);

        verify(valueOps).increment("seckill:stock:100");
    }

    @Test
    @DisplayName("SREM 返回 0：用户不在集合（已回滚过），不 INCR 库存")
    void rollbackIdempotent() {
        when(redis.opsForSet()).thenReturn(setOps);
        when(setOps.remove(anyString(), anyString())).thenReturn(0L);

        stockRollback.rollback(1L, 100L);

        verify(redis, never()).opsForValue();
    }
}
