package com.seckill.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.common.api.goods.ActivityInfoDTO;
import com.seckill.common.exception.BizException;
import com.seckill.common.redis.RedisKeys;
import com.seckill.common.result.ErrorCode;
import com.seckill.seckill.dto.SeckillMessage;
import com.seckill.seckill.dto.SeckillOrderDTO;
import com.seckill.seckill.entity.LocalMessage;
import com.seckill.seckill.entity.SeckillRecord;
import com.seckill.seckill.feign.FeignResultUtils;
import com.seckill.seckill.feign.GoodsClient;
import com.seckill.seckill.feign.UserClient;
import com.seckill.seckill.mapper.LocalMessageMapper;
import com.seckill.seckill.mapper.SeckillRecordMapper;
import com.seckill.seckill.service.SeckillOrderService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀下单（M4 异步排队模式 + M5 微服务化）。
 *
 * 链路：
 * 1. 幂等快路径：同一 requestId 已排队/已下单 → 直接返回，不重复预扣
 * 2. 活动信息（Redis 优先，miss 走 Feign 调 goods）+ 时间窗 + 用户校验（Feign 调 user）
 * 3. Lua 原子预扣（快闸门）
 * 4. 本地事务：流水(t_seckill_record) + 消息(t_local_message) 同事务落库
 * 5. 事务提交后异步发 MQ：成功 → 消息置"已发送"；失败 → 留表，补偿任务兜底
 * 6. 立即返回"排队中"——入口 RT 目标 < 50ms，洪峰由 MQ 缓冲，消费端异步落单
 *
 * M5 拆分变化：原 GoodsMapper/StockMapper/UserMapper/SeckillActivityMapper/ActivityPreheatService
 * 的直接访问全部改成 Feign 调 goods-service/user-service（跨服务不碰对方数据库）。
 * 活动信息热路径仍走共享 Redis 的 seckill:activity:{id} 缓存（goods 预热写入），
 * 只有缓存 miss 才跨服务兜底——这是拆分后入口性能不塌的关键。
 *
 * 降级：Redis 整体不可用 → DB 同步直写（M2 链路），可用性优先。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillOrderServiceImpl implements SeckillOrderService {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> seckillPreDeductScript;
    private final ObjectMapper objectMapper;
    private final GoodsClient goodsClient;
    private final UserClient userClient;
    private final DbOrderWriter dbOrderWriter;
    private final RecordMessageWriter recordMessageWriter;
    private final SeckillRecordMapper recordMapper;
    private final LocalMessageMapper messageMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final RedisStockRollback stockRollback;

    @Value("${seckill.mq.topic}")
    private String topic;

    @Override
    public void createOrder(SeckillOrderDTO dto) {
        // 1. 幂等快路径：requestId 是贯穿"预扣→消息→订单"的幂等键，
        //    重试请求（网络超时重发、前端重复点击）不会触发第二次预扣
        SeckillRecord existing = recordMapper.selectOne(
                new LambdaQueryWrapper<SeckillRecord>().eq(SeckillRecord::getRequestId, dto.getRequestId()));
        if (existing != null) {
            if (existing.getStatus() == 1) {
                throw new BizException(ErrorCode.ALREADY_ORDERED, "已下单，请勿重复提交");
            }
            if (existing.getStatus() == 0) {
                throw new BizException(ErrorCode.ALREADY_ORDERED, "排队中，请勿重复提交");
            }
            // status=2（上次已回滚）：当作新请求继续走流程
        }

        // 2. 活动信息与时间窗校验（活动信息 Redis 优先，miss 走 Feign 调 goods-service）
        ActivityInfoDTO activity = loadActivity(dto.getActivityId());
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getStartTime())) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_STARTED);
        }
        if (now.isAfter(activity.getEndTime())) {
            throw new BizException(ErrorCode.ACTIVITY_ENDED);
        }
        // 用户存在性校验：原 UserMapper 直连，拆分后 Feign 调 user-service
        if (!Boolean.TRUE.equals(FeignResultUtils.unwrap(userClient.userExists(dto.getUserId())))) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        try {
            doSeckill(dto, activity);
        } catch (DataAccessException e) {
            // 3. Redis 故障降级：同步直写，保证可用性与不超卖
            log.error("Redis 不可用，降级 DB 直写: {}", e.getMessage());
            dbOrderWriter.writeOrder(dto, activity, loadGoodsName(activity));
        }
    }

    @Override
    public Integer getRedisStock(Long activityId) {
        String value = redis.opsForValue().get(RedisKeys.stock(activityId));
        return value == null ? -1 : Integer.parseInt(value);
    }

    /** Lua 预扣 → 落流水消息 → 异步发送 */
    private void doSeckill(SeckillOrderDTO dto, ActivityInfoDTO activity) {
        Long result = executePreDeduct(dto.getUserId(), activity.getId());
        if (result == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR);
        }
        switch (result.intValue()) {
            case 1 -> {
                // 4. 组装消息（商品名快照走 Feign 取一次），事务落"流水+消息"
                SeckillMessage message = buildMessage(dto, activity);
                try {
                    recordMessageWriter.write(dto.getRequestId(), dto.getUserId(),
                            dto.getActivityId(), topic, toJson(message));
                } catch (DuplicateKeyException e) {
                    // 并发下同一 requestId 撞唯一索引：视为重复提交，回滚本次预扣
                    rollbackPreDeduct(dto.getUserId(), activity.getId());
                    throw new BizException(ErrorCode.ALREADY_ORDERED, "排队中，请勿重复提交");
                } catch (BizException e) {
                    rollbackPreDeduct(dto.getUserId(), activity.getId());
                    throw e;
                }
                // 5. 事务提交后异步发送；发送失败不阻塞入口，由补偿任务兜底
                asyncSend(message);
            }
            case -1 -> throw new BizException(ErrorCode.ALREADY_ORDERED);
            case -2 -> throw new BizException(ErrorCode.STOCK_NOT_ENOUGH);
            case -3 -> {
                // 未预热兜底：调 goods-service 补预热（预热逻辑单点留在 goods）后重试一次
                FeignResultUtils.unwrap(goodsClient.preheat(activity.getId()));
                Long retry = executePreDeduct(dto.getUserId(), activity.getId());
                if (retry != null && retry.intValue() == 1) {
                    SeckillMessage message = buildMessage(dto, activity);
                    try {
                        recordMessageWriter.write(dto.getRequestId(), dto.getUserId(),
                                dto.getActivityId(), topic, toJson(message));
                    } catch (DuplicateKeyException e) {
                        rollbackPreDeduct(dto.getUserId(), activity.getId());
                        throw new BizException(ErrorCode.ALREADY_ORDERED, "排队中，请勿重复提交");
                    }
                    asyncSend(message);
                } else if (retry != null && retry.intValue() == -1) {
                    throw new BizException(ErrorCode.ALREADY_ORDERED);
                } else if (retry != null && retry.intValue() == -2) {
                    throw new BizException(ErrorCode.STOCK_NOT_ENOUGH);
                } else {
                    throw new BizException(ErrorCode.INTERNAL_ERROR, "活动未就绪，请稍后重试");
                }
            }
            default -> throw new BizException(ErrorCode.INTERNAL_ERROR);
        }
    }

    /** 异步发送 MQ：成功把本地消息置"已发送"，失败留表等待补偿任务重发 */
    private void asyncSend(SeckillMessage message) {
        rocketMQTemplate.asyncSend(topic, message, new SendCallback() {
            @Override
            public void onSuccess(SendResult sendResult) {
                messageMapper.update(null, new LambdaUpdateWrapper<LocalMessage>()
                        .set(LocalMessage::getStatus, 1)
                        .eq(LocalMessage::getMessageId, message.getRequestId()));
            }

            @Override
            public void onException(Throwable e) {
                log.warn("MQ 发送失败，留表待补偿: requestId={}, err={}", message.getRequestId(), e.getMessage());
            }
        });
    }

    /** 组装消息体：商品名/成交价快照，消费端无需再查商品库 */
    private SeckillMessage buildMessage(SeckillOrderDTO dto, ActivityInfoDTO activity) {
        SeckillMessage message = new SeckillMessage();
        message.setRequestId(dto.getRequestId());
        message.setUserId(dto.getUserId());
        message.setActivityId(dto.getActivityId());
        message.setGoodsId(activity.getGoodsId());
        message.setGoodsName(loadGoodsName(activity));
        message.setPrice(activity.getSeckillPrice());
        return message;
    }

    /** 商品名快照：Feign 调 goods-service，商品不存在降级为空串 */
    private String loadGoodsName(ActivityInfoDTO activity) {
        String name = FeignResultUtils.unwrap(goodsClient.goodsName(activity.getGoodsId()));
        return name == null ? "" : name;
    }

    /**
     * 幂等回滚预扣：只有 SREM 确实移除了该用户（返回 1）才 INCR 库存，
     * 防止重复回滚把库存多加。
     */
    private void rollbackPreDeduct(Long userId, Long activityId) {
        stockRollback.rollback(userId, activityId);
    }

    private Long executePreDeduct(Long userId, Long activityId) {
        return redis.execute(seckillPreDeductScript,
                List.of(RedisKeys.stock(activityId), RedisKeys.userSet(activityId)),
                String.valueOf(userId));
    }

    /** 活动信息：Redis 预热缓存优先（goods 写入的共享契约），miss 走 Feign 兜底 */
    private ActivityInfoDTO loadActivity(Long activityId) {
        String json = redis.opsForValue().get(RedisKeys.activityInfo(activityId));
        if (json != null) {
            return readActivity(json);
        }
        return FeignResultUtils.unwrap(goodsClient.activity(activityId));
    }

    @SneakyThrows
    private ActivityInfoDTO readActivity(String json) {
        return objectMapper.readValue(json, ActivityInfoDTO.class);
    }

    @SneakyThrows
    private String toJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }
}
