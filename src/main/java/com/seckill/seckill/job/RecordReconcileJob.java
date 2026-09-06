package com.seckill.seckill.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.seckill.goods.entity.SeckillActivity;
import com.seckill.goods.mapper.SeckillActivityMapper;
import com.seckill.order.entity.Order;
import com.seckill.order.mapper.OrderMapper;
import com.seckill.seckill.entity.SeckillRecord;
import com.seckill.seckill.mapper.SeckillRecordMapper;
import com.seckill.seckill.service.impl.RedisStockRollback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 流水对账任务（可靠消息的第四层兜底，每小时一次）。
 * 兜底修复"状态没推进"的异常账目：
 * - 流水=已预扣 且订单已存在 → 消费端漏更新状态 → 置"已下单"
 * - 流水=已预扣 且活动已结束仍无订单 → 预扣丢失 → 回滚 Redis 预扣 + 置"已回滚"
 *
 * 加上消费端幂等与 MQ 重试，任何链路遗漏最终都会被这张流水表揪出来。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecordReconcileJob {

    private final SeckillRecordMapper recordMapper;
    private final OrderMapper orderMapper;
    private final SeckillActivityMapper activityMapper;
    private final RedisStockRollback stockRollback;

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public void run() {
        // 只扫 5 分钟前的流水，避免和正常消费链路赛跑
        List<SeckillRecord> pending = recordMapper.selectList(
                new LambdaQueryWrapper<SeckillRecord>()
                        .eq(SeckillRecord::getStatus, 0)
                        .lt(SeckillRecord::getCreateTime, LocalDateTime.now().minusMinutes(5))
                        .last("LIMIT 500"));

        for (SeckillRecord record : pending) {
            Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                    .eq(Order::getRequestId, record.getRequestId()));
            if (order != null) {
                // 订单已落库但流水没推进：消费端漏更新，补齐状态
                record.setStatus(1);
                recordMapper.updateById(record);
                log.info("对账补齐: requestId={} 订单已存在，流水置已下单", record.getRequestId());
                continue;
            }
            SeckillActivity activity = activityMapper.selectById(record.getActivityId());
            if (activity == null || activity.getEndTime().isBefore(LocalDateTime.now())) {
                // 活动已结束仍无订单：这条预扣彻底丢失，回滚 Redis 释放库存
                stockRollback.rollback(record.getUserId(), record.getActivityId());
                record.setStatus(2);
                recordMapper.updateById(record);
                log.warn("对账回滚: requestId={} 活动已结束且无订单，已回滚预扣", record.getRequestId());
            }
        }
    }
}
