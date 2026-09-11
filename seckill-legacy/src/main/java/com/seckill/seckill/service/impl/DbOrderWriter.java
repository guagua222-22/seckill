package com.seckill.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.goods.entity.Goods;
import com.seckill.goods.entity.SeckillActivity;
import com.seckill.goods.entity.Stock;
import com.seckill.goods.mapper.GoodsMapper;
import com.seckill.goods.mapper.StockMapper;
import com.seckill.order.entity.Order;
import com.seckill.order.mapper.OrderMapper;
import com.seckill.seckill.dto.SeckillOrderDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB 落单器（M3 拆成独立 Bean 的原因，面试必问）：
 * Spring 的事务注解靠 AOP 代理生效，同一个类内部"自调用"（this.xxx()）不会经过代理，
 * 事务注解会静默失效。而秒杀入口方法不能整体加事务（Lua 预扣在事务外），
 * 所以把 DB 落单拆到独立 Bean，入口跨 Bean 调用它，事务才能正常开启。
 *
 * 本类同时是 Redis 故障时的降级路径：条件更新 + 唯一索引双兜底，不经过 Redis 也绝不超卖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbOrderWriter {

    private final GoodsMapper goodsMapper;
    private final StockMapper stockMapper;
    private final OrderMapper orderMapper;

    @Transactional
    public Long writeOrder(SeckillOrderDTO dto, SeckillActivity activity) {
        // 1. 一人一单预检：命中直接返回，避免无效扣库存
        //（Redis 正常路径由 Lua 判重，这里是降级路径与并发窗口兜底）
        Long ordered = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, dto.getUserId())
                .eq(Order::getActivityId, dto.getActivityId()));
        if (ordered > 0) {
            throw new BizException(ErrorCode.ALREADY_ORDERED);
        }

        // 2. 条件更新扣库存：available_stock > 0 是防超卖的核心条件，
        //    受影响行数 = 0 说明库存已耗尽
        int rows = stockMapper.update(null, new LambdaUpdateWrapper<Stock>()
                .setSql("available_stock = available_stock - 1")
                .setSql("sold_count = sold_count + 1")
                .setSql("version = version + 1")
                .eq(Stock::getGoodsId, activity.getGoodsId())
                .gt(Stock::getAvailableStock, 0));
        if (rows == 0) {
            throw new BizException(ErrorCode.STOCK_NOT_ENOUGH);
        }

        // 3. 落订单：商品名/价格快照；并发穿透预检时靠唯一索引兜底，
        //    冲突说明已抢过，异常抛出后整个事务回滚（库存扣减一并撤销）
        Goods goods = goodsMapper.selectById(activity.getGoodsId());
        Order order = new Order();
        order.setOrderNo(IdWorker.getIdStr());
        order.setUserId(dto.getUserId());
        order.setActivityId(dto.getActivityId());
        order.setGoodsId(activity.getGoodsId());
        order.setGoodsName(goods == null ? "" : goods.getGoodsName());
        order.setPrice(activity.getSeckillPrice());
        order.setStatus(0);
        order.setRequestId(dto.getRequestId());
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            log.warn("唯一索引拦截重复下单: userId={}, activityId={}, requestId={}",
                    dto.getUserId(), dto.getActivityId(), dto.getRequestId());
            throw new BizException(ErrorCode.ALREADY_ORDERED);
        }
        return order.getId();
    }
}
