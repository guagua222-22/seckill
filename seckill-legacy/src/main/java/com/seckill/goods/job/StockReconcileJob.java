package com.seckill.goods.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.seckill.common.redis.RedisKeys;
import com.seckill.goods.entity.SeckillActivity;
import com.seckill.goods.entity.Stock;
import com.seckill.goods.mapper.SeckillActivityMapper;
import com.seckill.goods.mapper.StockMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 活动结束对账任务（每小时执行一次）。
 * 职责：
 * 1. 校验 Redis 预扣剩余与 DB 可用库存是否一致——不一致说明预扣与落单之间出现了缺口
 *   （如 Redis 回滚失败），以 DB 为准回写 Redis 并告警日志（DB 是最终事实源）；
 * 2. 清理已结束活动的 Redis key（库存/用户集合/活动信息），防止 key 堆积。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockReconcileJob {

    private final SeckillActivityMapper activityMapper;
    private final StockMapper stockMapper;
    private final StringRedisTemplate redis;

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void run() {
        LocalDateTime now = LocalDateTime.now();
        // 只处理最近一天内结束的活动，避免每次都全表扫历史数据
        List<SeckillActivity> ended = activityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>()
                        .lt(SeckillActivity::getEndTime, now)
                        .gt(SeckillActivity::getEndTime, now.minusDays(1)));

        for (SeckillActivity activity : ended) {
            String stockKey = RedisKeys.stock(activity.getId());
            String remain = redis.opsForValue().get(stockKey);
            if (remain == null) {
                continue; // 未预热过或已清理
            }
            Stock stock = stockMapper.selectOne(
                    new LambdaQueryWrapper<Stock>().eq(Stock::getGoodsId, activity.getGoodsId()));
            if (stock != null && Integer.parseInt(remain) != stock.getAvailableStock()) {
                log.warn("对账不一致: activityId={}, redis剩余={}, db可用={}",
                        activity.getId(), remain, stock.getAvailableStock());
                redis.opsForValue().set(stockKey, String.valueOf(stock.getAvailableStock()));
            }
            redis.delete(List.of(stockKey,
                    RedisKeys.userSet(activity.getId()),
                    RedisKeys.activityInfo(activity.getId())));
        }
    }
}
