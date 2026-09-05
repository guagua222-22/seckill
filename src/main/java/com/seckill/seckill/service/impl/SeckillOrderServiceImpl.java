package com.seckill.seckill.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.common.exception.BizException;
import com.seckill.common.redis.RedisKeys;
import com.seckill.common.result.ErrorCode;
import com.seckill.goods.entity.SeckillActivity;
import com.seckill.goods.mapper.SeckillActivityMapper;
import com.seckill.goods.service.ActivityPreheatService;
import com.seckill.seckill.dto.SeckillOrderDTO;
import com.seckill.seckill.service.SeckillOrderService;
import com.seckill.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀下单（M3：Redis Lua 预扣 + DB 落单双保险）。
 *
 * 完整链路：
 * 1. 活动信息优先读 Redis（预热时写入），miss 回 DB
 * 2. 时间窗校验（服务端时间；Redis 信息只有时间窗相关字段，status 不可信）
 * 3. Lua 原子预扣：库存 -1 + 登记已购（返回码契约见 seckill_pre_deduct.lua）
 * 4. DB 事务落单：条件更新 + 唯一索引兜底——即使 Redis 与 DB 出现偏差也不超卖
 * 5. DB 失败 → 幂等回滚 Redis 预扣（INCR 库存 + SREM 用户），保持两侧一致
 *
 * 降级策略：Redis 整体不可用时，跳过 Lua 直接走 DB 链路（M2 逻辑），
 * 条件更新依然保证不超卖，只是性能退化——"可降级"是生产系统的底线能力。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillOrderServiceImpl implements SeckillOrderService {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> seckillPreDeductScript;
    private final ObjectMapper objectMapper;
    private final SeckillActivityMapper activityMapper;
    private final UserMapper userMapper;
    private final ActivityPreheatService preheatService;
    private final DbOrderWriter dbOrderWriter;

    @Override
    public Long createOrder(SeckillOrderDTO dto) {
        // 1. 活动信息（Redis 优先）与时间窗校验
        SeckillActivity activity = loadActivity(dto.getActivityId());
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getStartTime())) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_STARTED);
        }
        if (now.isAfter(activity.getEndTime())) {
            throw new BizException(ErrorCode.ACTIVITY_ENDED);
        }
        // 2. 用户存在性快速失败
        if (userMapper.selectById(dto.getUserId()) == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        try {
            return doSeckill(dto, activity);
        } catch (DataAccessException e) {
            // 3. Redis 故障降级：直接走 DB 链路，保证可用性与不超卖
            log.error("Redis 不可用，降级 DB 直写: {}", e.getMessage());
            return dbOrderWriter.writeOrder(dto, activity);
        }
    }

    @Override
    public Integer getRedisStock(Long activityId) {
        String value = redis.opsForValue().get(RedisKeys.stock(activityId));
        return value == null ? -1 : Integer.parseInt(value);
    }

    /** 执行 Lua 预扣并落单；-3（未预热）时补预热后重试一次 */
    private Long doSeckill(SeckillOrderDTO dto, SeckillActivity activity) {
        Long result = executePreDeduct(dto.getUserId(), activity.getId());
        if (result == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR);
        }
        switch (result.intValue()) {
            case 1:
                return writeOrderWithRollback(dto, activity);
            case -1:
                throw new BizException(ErrorCode.ALREADY_ORDERED);
            case -2:
                throw new BizException(ErrorCode.STOCK_NOT_ENOUGH);
            case -3:
                // 未预热（理论上预热任务已覆盖，这里兜底）：补预热后重试一次
                preheatService.preheat(activity);
                Long retry = executePreDeduct(dto.getUserId(), activity.getId());
                if (retry != null && retry.intValue() == 1) {
                    return writeOrderWithRollback(dto, activity);
                }
                if (retry != null && retry.intValue() == -1) {
                    throw new BizException(ErrorCode.ALREADY_ORDERED);
                }
                if (retry != null && retry.intValue() == -2) {
                    throw new BizException(ErrorCode.STOCK_NOT_ENOUGH);
                }
                throw new BizException(ErrorCode.INTERNAL_ERROR, "活动未就绪，请稍后重试");
            default:
                throw new BizException(ErrorCode.INTERNAL_ERROR);
        }
    }

    /** DB 落单失败时回滚 Redis 预扣，保证"预扣-落单"两侧账目一致 */
    private Long writeOrderWithRollback(SeckillOrderDTO dto, SeckillActivity activity) {
        try {
            return dbOrderWriter.writeOrder(dto, activity);
        } catch (BizException e) {
            rollbackPreDeduct(dto.getUserId(), activity.getId());
            throw e;
        }
    }

    /**
     * 幂等回滚预扣：只有 SREM 确实移除了该用户（返回 1）才 INCR 库存。
     * 若不加这个判断，重复回滚会把库存多加——"回滚本身也必须幂等"。
     */
    private void rollbackPreDeduct(Long userId, Long activityId) {
        Long removed = redis.opsForSet().remove(RedisKeys.userSet(activityId), String.valueOf(userId));
        if (removed != null && removed == 1) {
            redis.opsForValue().increment(RedisKeys.stock(activityId));
        }
    }

    /** 执行预扣脚本（EVALSHA，脚本未加载时自动回退 EVAL） */
    private Long executePreDeduct(Long userId, Long activityId) {
        return redis.execute(seckillPreDeductScript,
                List.of(RedisKeys.stock(activityId), RedisKeys.userSet(activityId)),
                String.valueOf(userId));
    }

    /** 活动信息：Redis 预热缓存优先，miss 回 DB（兼容未预热窗口） */
    private SeckillActivity loadActivity(Long activityId) {
        String json = redis.opsForValue().get(RedisKeys.activityInfo(activityId));
        if (json != null) {
            return readActivity(json);
        }
        SeckillActivity activity = activityMapper.selectById(activityId);
        if (activity == null) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
        return activity;
    }

    @SneakyThrows
    private SeckillActivity readActivity(String json) {
        return objectMapper.readValue(json, SeckillActivity.class);
    }
}
