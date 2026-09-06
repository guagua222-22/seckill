package com.seckill.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.goods.entity.SeckillActivity;
import com.seckill.seckill.dto.SeckillMessage;
import com.seckill.seckill.dto.SeckillOrderDTO;
import com.seckill.seckill.entity.SeckillRecord;
import com.seckill.seckill.mapper.SeckillRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 消费端核心处理逻辑（独立成 Service 便于单测；RocketMQListener 只是薄封装）。
 *
 * 消费链路与幂等设计（三层）：
 * 1. Redis SETNX（24h）拦截 MQ 重复投递——绝大多数重复在这里被挡掉
 * 2. Redisson 用户级分布式锁——同用户消息串行落库，减少唯一键冲突的无效往返
 * 3. DB 唯一索引（uk_user_activity / uk_request_id）最终兜底——锁和 SETNX 都失效也不会重复下单
 *
 * 失败分类处理（消费端不能盲目重试）：
 * - 已下单（幂等成功）→ 状态对齐后 ACK
 * - 库存不足（Redis 与 DB 偏差）→ 回滚预扣、流水置"已回滚"、ACK（重试无意义）
 * - 其他异常 → 抛出，触发 RocketMQ 重试（默认 16 次，耗尽进死信队列）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillOrderConsumerService {

    private final StringRedisTemplate redis;
    private final RedissonClient redissonClient;
    private final DbOrderWriter dbOrderWriter;
    private final SeckillRecordMapper recordMapper;
    private final RedisStockRollback stockRollback;

    public void process(SeckillMessage message) {
        // 1. 消费幂等第一层：SETNX 去重（TTL 24h，覆盖 MQ 重试窗口）
        Boolean first = redis.opsForValue().setIfAbsent(
                "order:dedup:" + message.getRequestId(), "1", Duration.ofHours(24));
        if (!Boolean.TRUE.equals(first)) {
            log.warn("重复消息被 SETNX 拦截: requestId={}", message.getRequestId());
            return;
        }

        // 2. 用户级分布式锁：同用户并发消息串行化（性能优化，非正确性必需——
        //    正确性由 DB 唯一索引保证，锁的作用是减少无谓的冲突与回滚）
        RLock lock = redissonClient.getLock("lock:seckill:user:" + message.getUserId());
        boolean locked = false;
        try {
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new RuntimeException("获取用户锁超时，稍后重试");
            }
            // 3. DB 落单（条件更新 + 唯一索引双兜底）
            SeckillOrderDTO dto = toDto(message);
            SeckillActivity activity = toActivity(message);
            dbOrderWriter.writeOrder(dto, activity);
            // 4. 流水推进到"已下单"
            updateRecordStatus(message.getRequestId(), 1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("获取用户锁被中断，稍后重试", e);
        } catch (BizException e) {
            if (e.getCode() == ErrorCode.ALREADY_ORDERED.getCode()) {
                // 幂等成功场景（穿透 SETNX 的重复消息/用户已抢过）：对齐状态后 ACK
                updateRecordStatus(message.getRequestId(), 1);
                log.warn("消费端幂等拦截: requestId={}", message.getRequestId());
                return;
            }
            if (e.getCode() == ErrorCode.STOCK_NOT_ENOUGH.getCode()) {
                // Redis 与 DB 出现偏差（如对账前库存已耗尽）：回滚预扣，终态处理
                stockRollback.rollback(message.getUserId(), message.getActivityId());
                updateRecordStatus(message.getRequestId(), 2);
                log.warn("消费端库存不足，已回滚预扣: requestId={}", message.getRequestId());
                return;
            }
            throw e; // 其他业务异常：交由 MQ 重试
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    private void updateRecordStatus(String requestId, int status) {
        recordMapper.update(null, new LambdaUpdateWrapper<SeckillRecord>()
                .set(SeckillRecord::getStatus, status)
                .eq(SeckillRecord::getRequestId, requestId));
    }

    private SeckillOrderDTO toDto(SeckillMessage message) {
        SeckillOrderDTO dto = new SeckillOrderDTO();
        dto.setUserId(message.getUserId());
        dto.setActivityId(message.getActivityId());
        dto.setRequestId(message.getRequestId());
        return dto;
    }

    /** 从消息体还原活动关键字段（落单只需要 goodsId 与成交价） */
    private SeckillActivity toActivity(SeckillMessage message) {
        SeckillActivity activity = new SeckillActivity();
        activity.setId(message.getActivityId());
        activity.setGoodsId(message.getGoodsId());
        activity.setSeckillPrice(message.getPrice());
        return activity;
    }
}
