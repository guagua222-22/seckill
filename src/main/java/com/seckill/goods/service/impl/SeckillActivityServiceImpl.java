package com.seckill.goods.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.goods.dto.ActivityDTO;
import com.seckill.goods.entity.Goods;
import com.seckill.goods.entity.SeckillActivity;
import com.seckill.goods.entity.Stock;
import com.seckill.goods.mapper.GoodsMapper;
import com.seckill.goods.mapper.SeckillActivityMapper;
import com.seckill.goods.mapper.StockMapper;
import com.seckill.goods.service.SeckillActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SeckillActivityServiceImpl implements SeckillActivityService {

    private final SeckillActivityMapper activityMapper;
    private final GoodsMapper goodsMapper;
    private final StockMapper stockMapper;

    @Override
    @Transactional
    public SeckillActivity create(ActivityDTO dto) {
        Goods goods = goodsMapper.selectById(dto.getGoodsId());
        if (goods == null) {
            throw new BizException(ErrorCode.GOODS_NOT_FOUND);
        }
        if (!dto.getEndTime().isAfter(dto.getStartTime())) {
            throw new BizException(ErrorCode.ACTIVITY_TIME_INVALID);
        }
        SeckillActivity activity = new SeckillActivity();
        activity.setActivityName(dto.getActivityName());
        activity.setGoodsId(dto.getGoodsId());
        activity.setSeckillPrice(dto.getSeckillPrice());
        activity.setTotalStock(dto.getTotalStock());
        activity.setStartTime(dto.getStartTime());
        activity.setEndTime(dto.getEndTime());
        LocalDateTime now = LocalDateTime.now();
        activity.setStatus(now.isBefore(dto.getStartTime()) ? 0 : 1);
        activity.setIsHot(dto.getIsHot() == null ? 0 : dto.getIsHot());
        activityMapper.insert(activity);

        // 活动创建即灌入秒杀库存（活动库存只此一个入口修改，不开放直接改库存接口）
        Stock stock = stockMapper.selectOne(
                new LambdaQueryWrapper<Stock>().eq(Stock::getGoodsId, dto.getGoodsId()));
        if (stock == null) {
            stock = new Stock();
            stock.setGoodsId(dto.getGoodsId());
            stock.setVersion(0);
            stock.setSoldCount(0);
        }
        stock.setTotalStock(dto.getTotalStock());
        stock.setAvailableStock(dto.getTotalStock());
        if (stock.getId() == null) {
            stockMapper.insert(stock);
        } else {
            stockMapper.updateById(stock);
        }
        return activity;
    }

    @Override
    public SeckillActivity getById(Long id) {
        SeckillActivity activity = activityMapper.selectById(id);
        if (activity == null) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_FOUND);
        }
        return activity;
    }

    @Override
    public Page<SeckillActivity> page(int page, int size) {
        return activityMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<SeckillActivity>().orderByDesc(SeckillActivity::getCreateTime));
    }
}
