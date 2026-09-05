package com.seckill.goods.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.goods.dto.GoodsDTO;
import com.seckill.goods.entity.Goods;
import com.seckill.goods.entity.Stock;
import com.seckill.goods.mapper.GoodsMapper;
import com.seckill.goods.mapper.StockMapper;
import com.seckill.goods.service.GoodsService;
import com.seckill.goods.vo.GoodsDetailVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GoodsServiceImpl implements GoodsService {

    private final GoodsMapper goodsMapper;
    private final StockMapper stockMapper;

    @Override
    @Transactional
    public Goods create(GoodsDTO dto) {
        Goods goods = new Goods();
        goods.setGoodsName(dto.getGoodsName());
        goods.setDescription(dto.getDescription());
        goods.setNormalPrice(dto.getNormalPrice());
        goods.setStatus(1);
        goodsMapper.insert(goods);

        // 秒杀库存独立成表：商品创建时初始化一行 0 库存，活动创建时再灌入
        Stock stock = new Stock();
        stock.setGoodsId(goods.getId());
        stock.setTotalStock(0);
        stock.setAvailableStock(0);
        stock.setSoldCount(0);
        stock.setVersion(0);
        stockMapper.insert(stock);
        return goods;
    }

    @Override
    public Goods update(Long id, GoodsDTO dto) {
        Goods goods = goodsMapper.selectById(id);
        if (goods == null) {
            throw new BizException(ErrorCode.GOODS_NOT_FOUND);
        }
        goods.setGoodsName(dto.getGoodsName());
        goods.setDescription(dto.getDescription());
        goods.setNormalPrice(dto.getNormalPrice());
        goodsMapper.updateById(goods);
        return goods;
    }

    @Override
    public GoodsDetailVO getDetail(Long id) {
        Goods goods = goodsMapper.selectById(id);
        if (goods == null) {
            throw new BizException(ErrorCode.GOODS_NOT_FOUND);
        }
        Stock stock = stockMapper.selectOne(
                new LambdaQueryWrapper<Stock>().eq(Stock::getGoodsId, id));
        GoodsDetailVO vo = new GoodsDetailVO();
        vo.setId(goods.getId());
        vo.setGoodsName(goods.getGoodsName());
        vo.setDescription(goods.getDescription());
        vo.setNormalPrice(goods.getNormalPrice());
        vo.setStatus(goods.getStatus());
        vo.setCreateTime(goods.getCreateTime());
        if (stock != null) {
            vo.setTotalStock(stock.getTotalStock());
            vo.setAvailableStock(stock.getAvailableStock());
            vo.setSoldCount(stock.getSoldCount());
        }
        return vo;
    }

    @Override
    public Page<Goods> page(int page, int size) {
        return goodsMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Goods>().orderByDesc(Goods::getCreateTime));
    }
}
