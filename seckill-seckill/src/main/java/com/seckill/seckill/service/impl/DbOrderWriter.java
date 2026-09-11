package com.seckill.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.seckill.common.api.goods.ActivityInfoDTO;
import com.seckill.common.api.goods.DeductStockRequest;
import com.seckill.common.api.goods.RollbackStockRequest;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.seckill.dto.SeckillOrderDTO;
import com.seckill.seckill.feign.FeignResultUtils;
import com.seckill.seckill.feign.GoodsClient;
import com.seckill.seckill.order.entity.Order;
import com.seckill.seckill.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB 落单器（M3 拆成独立 Bean 的原因，面试必问）：
 * Spring 的事务注解靠 AOP 代理生效，同一个类内部自调用不会经过代理，
 * 事务注解会静默失效。而秒杀入口方法不能整体加事务（Lua 预扣在事务外），
 * 所以把 DB 落单拆到独立 Bean，入口跨 Bean 调用它，事务才能正常开启。
 *
 * 本类同时是 Redis 故障时的降级路径（与消费端共用）。
 *
 * M5 拆分后的跨库补偿设计（核心考点）：
 * 拆分前"扣库存 + 插订单"在同一个本地数据库事务里，失败整体回滚；
 * 拆分后库存表归 goods-service，这里只剩本地事务。顺序设计——
 * ① 一人一单预检（本地查订单）
 * ② Feign 扣 DB 库存（goods 侧事务 = 条件更新 + t_stock_operation 流水，requestId 幂等）
 * ③ 本地插订单（唯一索引兜底）
 * 先扣库存后插单：库存是稀缺资源先占住，插单失败只需补偿库存（幂等一步）；
 * 反过来先插单失败时要取消订单，订单对外可见、状态机复杂。
 * 补偿兜底三层：同步补偿 → MQ 重投重入（全链路幂等）→ 对账任务每小时兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbOrderWriter {

    private final OrderMapper orderMapper;
    private final GoodsClient goodsClient;

    /**
     * @param goodsName 商品名快照，由调用方传入：消费端从消息快照取，降级路径从 Feign 取，
     *                  避免本类每单多一次 Feign 调用
     */
    @Transactional
    public Long writeOrder(SeckillOrderDTO dto, ActivityInfoDTO activity, String goodsName) {
        // 1. 一人一单预检：命中直接返回，避免无效扣库存
        //（Redis 正常路径由 Lua 判重，这里是降级路径与并发窗口兜底）
        Long ordered = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, dto.getUserId())
                .eq(Order::getActivityId, dto.getActivityId()));
        if (ordered > 0) {
            throw new BizException(ErrorCode.ALREADY_ORDERED);
        }

        // 2. Feign 扣 DB 库存（goods 侧条件更新 + 幂等流水同事务，requestId 与下单一致；
        //    重复调用/消息重投都不会二次扣减）
        FeignResultUtils.unwrap(goodsClient.deductStock(new DeductStockRequest(
                dto.getRequestId(), activity.getGoodsId(), dto.getActivityId(), 1)));

        // 3. 本地插订单：唯一索引兜底并发穿透预检
        Order order = new Order();
        order.setOrderNo(IdWorker.getIdStr());
        order.setUserId(dto.getUserId());
        order.setActivityId(dto.getActivityId());
        order.setGoodsId(activity.getGoodsId());
        order.setGoodsName(goodsName == null ? "" : goodsName);
        order.setPrice(activity.getSeckillPrice());
        order.setStatus(0);
        order.setRequestId(dto.getRequestId());
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            // 插单冲突 → 同步补偿库存（幂等）。若补偿也失败，
            // MQ 重投该消息会重新执行本方法，全链路幂等保证重入安全
            log.warn("唯一索引拦截重复下单，补偿库存: userId={}, activityId={}, requestId={}",
                    dto.getUserId(), dto.getActivityId(), dto.getRequestId());
            safeRollbackStock(dto.getRequestId());
            throw new BizException(ErrorCode.ALREADY_ORDERED);
        }
        return order.getId();
    }

    /** 补偿失败不能掩盖主异常（ALREADY_ORDERED），记 error 留给对账任务兜底 */
    private void safeRollbackStock(String requestId) {
        try {
            FeignResultUtils.unwrap(goodsClient.rollbackStock(new RollbackStockRequest(requestId)));
        } catch (Exception rollbackEx) {
            log.error("补偿库存失败，等待对账兜底: requestId={}, err={}", requestId, rollbackEx.getMessage());
        }
    }
}
