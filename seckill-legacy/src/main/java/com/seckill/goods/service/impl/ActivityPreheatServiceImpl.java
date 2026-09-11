package com.seckill.goods.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.common.redis.RedisKeys;
import com.seckill.goods.entity.SeckillActivity;
import com.seckill.goods.mapper.SeckillActivityMapper;
import com.seckill.goods.service.ActivityPreheatService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 活动预热实现。
 * 关键点：库存 key 必须用 SETNX（setIfAbsent）写入——
 * 如果重复预热用 SET 覆盖，会把已经扣减过的库存数字重置回 totalStock，直接造成超卖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityPreheatServiceImpl implements ActivityPreheatService {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SeckillActivityMapper activityMapper;

    @Override
    public void preheat(SeckillActivity activity) {
        // 1. 库存计数：SETNX 幂等写入（仅第一次预热生效）
        Boolean first = redis.opsForValue().setIfAbsent(
                RedisKeys.stock(activity.getId()),
                String.valueOf(activity.getTotalStock()));
        if (Boolean.TRUE.equals(first)) {
            log.info("活动预热库存: activityId={}, stock={}", activity.getId(), activity.getTotalStock());
        }

        // 2. 活动信息：每次预热都刷新（时间窗可能有调整），TTL 到活动结束后 1 小时自动回收
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", activity.getId());
        info.put("goodsId", activity.getGoodsId());
        info.put("seckillPrice", activity.getSeckillPrice());
        info.put("startTime", activity.getStartTime().toString());
        info.put("endTime", activity.getEndTime().toString());
        Duration ttl = Duration.between(LocalDateTime.now(), activity.getEndTime()).plusHours(1);
        redis.opsForValue().set(RedisKeys.activityInfo(activity.getId()), writeJson(info), ttl);
    }

    @Override
    public void preheatUpcoming() {
        LocalDateTime now = LocalDateTime.now();
        // 预热窗口：活动开始前 5 分钟内 + 已开始未结束的活动
        List<SeckillActivity> activities = activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>()
                        .le(SeckillActivity::getStartTime, now.plusMinutes(5))
                        .gt(SeckillActivity::getEndTime, now));
        activities.forEach(this::preheat);
    }

    @SneakyThrows
    private String writeJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }
}
