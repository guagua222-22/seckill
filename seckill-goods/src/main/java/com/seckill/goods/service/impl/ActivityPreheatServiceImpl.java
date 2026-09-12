package com.seckill.goods.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.common.redis.RedisKeys;
import com.seckill.goods.cache.HotGoodsLocalCache;
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
import java.util.stream.Collectors;

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
    private final HotGoodsLocalCache hotCache;

    @Override
    public void preheat(SeckillActivity activity) {
        // 1. 库存计数：SETNX 幂等写入（仅第一次预热生效）
        Boolean first = redis.opsForValue().setIfAbsent(
                RedisKeys.stock(activity.getId()),
                String.valueOf(activity.getTotalStock()));
        if (Boolean.TRUE.equals(first)) {
            log.info("活动预热库存: activityId={}, stock={}", activity.getId(), activity.getTotalStock());
        }

        // 热点活动立即标记：本地缓存不等下一轮定时刷新（最多 30s）就生效
        if (Integer.valueOf(1).equals(activity.getIsHot())) {
            hotCache.markHot(activity.getGoodsId());
        }

        // 2. 活动信息：每次预热都刷新（时间窗可能有调整），TTL 到活动结束后 1 小时自动回收
        //    这里是 goods 与 seckill 之间的共享 Redis 契约（对端反序列化成 ActivityInfoDTO）：
        //    activityName / goodsName 两个名字快照都必须带上——seckill 下单走缓存热路径时
        //    要靠它们写订单、流水、MQ 消息的冗余列；缺字段对端只会拿到 null（踩过坑），
        //    带上 goodsName 还能省掉每单一次"Feign 回查商品名"的跨服务调用
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", activity.getId());
        info.put("activityName", activity.getActivityName());
        info.put("goodsId", activity.getGoodsId());
        info.put("goodsName", activity.getGoodsName());
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

        // 热点商品集合整批替换：本轮扫到的活动就是"当前时间窗内该走本地缓存"的全集。
        // 用替换而不是逐个 markHot 累加，活动结束或 isHot 被改回 0 后集合会自动收缩，
        // 否则已经没人抢的商品会一直占着本地缓存、白白多出一层不一致窗口
        hotCache.refreshHotIds(activities.stream()
                .filter(a -> Integer.valueOf(1).equals(a.getIsHot()))
                .map(SeckillActivity::getGoodsId)
                .collect(Collectors.toSet()));
    }

    @SneakyThrows
    private String writeJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }
}
