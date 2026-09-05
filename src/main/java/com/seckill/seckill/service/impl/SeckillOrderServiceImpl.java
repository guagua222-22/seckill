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
import com.seckill.goods.mapper.SeckillActivityMapper;
import com.seckill.goods.mapper.StockMapper;
import com.seckill.order.entity.Order;
import com.seckill.order.mapper.OrderMapper;
import com.seckill.seckill.dto.SeckillOrderDTO;
import com.seckill.seckill.service.SeckillOrderService;
import com.seckill.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillOrderServiceImpl implements SeckillOrderService {

    private final SeckillActivityMapper activityMapper;
    private final GoodsMapper goodsMapper;
    private final StockMapper stockMapper;
    private final OrderMapper orderMapper;
    private final UserMapper userMapper;

    @Override
    @Transactional
    public Long createOrder(SeckillOrderDTO dto) {
        // 1. 时间窗校验（以服务端时间为准，status 字段只作展示）
        SeckillActivity activity = activityMapper.selectById(dto.getActivityId());
        if (activity == null) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getStartTime())) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_STARTED);
        }
        if (now.isAfter(activity.getEndTime())) {
            throw new BizException(ErrorCode.ACTIVITY_ENDED);
        }
        // 用户存在性校验（快速失败）
        if (userMapper.selectById(dto.getUserId()) == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        // 2. 一人一单预检：命中直接返回，避免无效扣库存
        Long ordered = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, dto.getUserId())
                .eq(Order::getActivityId, dto.getActivityId()));
        if (ordered > 0) {
            throw new BizException(ErrorCode.ALREADY_ORDERED);
        }

        // 3. 条件更新扣库存：available_stock > 0 是防超卖的核心条件，
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

        // 4. 落订单：商品名/价格快照，唯一索引兜底
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
            // 并发窗口期两个请求都通过了预检：唯一索引兜底，异常抛出后事务回滚已扣的库存
            log.warn("唯一索引拦截重复下单: userId={}, activityId={}, requestId={}",
                    dto.getUserId(), dto.getActivityId(), dto.getRequestId());
            throw new BizException(ErrorCode.ALREADY_ORDERED);
        }
        return order.getId();
    }
}
