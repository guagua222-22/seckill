package com.seckill.seckill.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.seckill.common.api.goods.ActivityInfoDTO;
import com.seckill.common.api.goods.RollbackStockRequest;
import com.seckill.seckill.entity.SeckillRecord;
import com.seckill.seckill.feign.FeignResultUtils;
import com.seckill.seckill.feign.GoodsClient;
import com.seckill.seckill.mapper.SeckillRecordMapper;
import com.seckill.seckill.order.entity.Order;
import com.seckill.seckill.order.mapper.OrderMapper;
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
 * - 流水=已预扣 且活动已结束仍无订单 → 预扣丢失 → 回滚 Redis 预扣 + 补偿 DB 库存 + 置"已回滚"
 *
 * M5 拆分变化：活动是否结束的判断从 SeckillActivityMapper 直连改成 Feign 调 goods-service
 * （跨服务不碰对方数据库）；回滚分支追加 DB 库存补偿（跨库后 Redis 预扣与 DB 库存都要还）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecordReconcileJob {

    private final SeckillRecordMapper recordMapper;
    private final OrderMapper orderMapper;
    private final GoodsClient goodsClient;
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
            // 活动状态走 Feign 查询：不存在或已结束都视为"活动终态"
            boolean activityEnded = true;
            try {
                ActivityInfoDTO activity = FeignResultUtils.unwrap(
                        goodsClient.activity(record.getActivityId()));
                activityEnded = activity.getEndTime().isBefore(LocalDateTime.now());
            } catch (Exception e) {
                // 查活动失败（服务不可用/活动不存在）：按未结束处理，留待下轮对账
                log.warn("对账查询活动失败，跳过本轮: activityId={}, err={}",
                        record.getActivityId(), e.getMessage());
                continue;
            }
            if (activityEnded) {
                // 活动已结束仍无订单：这条预扣彻底丢失，回滚 Redis 预扣 + 补偿 DB 库存
                stockRollback.rollback(record.getUserId(), record.getActivityId());
                try {
                    FeignResultUtils.unwrap(goodsClient.rollbackStock(
                            new RollbackStockRequest(record.getRequestId())));
                } catch (Exception e) {
                    log.error("对账补偿 DB 库存失败，下轮重试: requestId={}, err={}",
                            record.getRequestId(), e.getMessage());
                    continue; // 补偿没成功前不置终态，避免库存账目丢失
                }
                record.setStatus(2);
                recordMapper.updateById(record);
                log.warn("对账回滚: requestId={} 活动已结束且无订单，已回滚预扣并补偿库存", record.getRequestId());
            }
        }
    }
}
