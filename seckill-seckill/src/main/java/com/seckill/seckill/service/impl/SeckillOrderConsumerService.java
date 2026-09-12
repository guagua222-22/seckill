package com.seckill.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.seckill.common.api.goods.ActivityInfoDTO;
import com.seckill.common.api.goods.RollbackStockRequest;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.seckill.dto.SeckillMessage;
import com.seckill.seckill.dto.SeckillOrderDTO;
import com.seckill.seckill.entity.SeckillRecord;
import com.seckill.seckill.feign.FeignResultUtils;
import com.seckill.seckill.feign.GoodsClient;
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
 * M5 拆分后的 SETNX 语义修正（正确性关键）：
 * SETNX 命中 ≠ 处理完成。拆库后"扣库存成功 → 插单失败 → 补偿也失败"的消息会被 MQ 重投，
 * 若 SETNX 命中就直接 return，这条消息永远无法重入，DB 库存缺口补不上。
 * 所以命中后必须查流水状态：status=1/2（已终态）才是真正重复；status=0（排队中）
 * 说明上次处理中断，继续走处理逻辑——扣减/补偿/唯一索引全链路幂等，重入安全。
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
    private final GoodsClient goodsClient;
    private final SeckillRecordMapper recordMapper;
    private final RedisStockRollback stockRollback;

    public void process(SeckillMessage message) {
        // 1. 消费幂等第一层：SETNX 去重（TTL 24h，覆盖 MQ 重试窗口）
        Boolean first = redis.opsForValue().setIfAbsent(
                "order:dedup:" + message.getRequestId(), "1", Duration.ofHours(24));
        if (!Boolean.TRUE.equals(first)) {
            // M5 语义修正：SETNX 命中后必须查流水状态再决定是否跳过
            //（status=0 说明上次处理中断，需要重入继续，见类注释）
            SeckillRecord record = recordMapper.selectOne(new LambdaQueryWrapper<SeckillRecord>()
                    .eq(SeckillRecord::getRequestId, message.getRequestId()));
            if (record == null || record.getStatus() != 0) {
                log.warn("重复消息被 SETNX 拦截: requestId={}", message.getRequestId());
                return;
            }
            log.warn("SETNX 命中但流水未终态，重入处理: requestId={}", message.getRequestId());
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
            // 3. DB 落单（Feign 扣库存 + 本地插订单，唯一索引双兜底）
            SeckillOrderDTO dto = toDto(message);
            ActivityInfoDTO activity = toActivity(message);
            dbOrderWriter.writeOrder(dto, activity, message.getGoodsName(), message.getUsername());
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
                // Redis 与 DB 出现偏差（如对账前库存已耗尽）：回滚预扣 + 补偿 DB 库存，终态处理
                stockRollback.rollback(message.getUserId(), message.getActivityId());
                FeignResultUtils.unwrap(goodsClient.rollbackStock(
                        new RollbackStockRequest(message.getRequestId())));
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

    /** 从消息体还原活动关键字段（落单需要 goodsId、活动名与成交价快照） */
    private ActivityInfoDTO toActivity(SeckillMessage message) {
        ActivityInfoDTO activity = new ActivityInfoDTO();
        activity.setId(message.getActivityId());
        activity.setActivityName(message.getActivityName());
        activity.setGoodsId(message.getGoodsId());
        activity.setSeckillPrice(message.getPrice());
        return activity;
    }
}
