package com.seckill.goods.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.goods.dto.GoodsDTO;
import com.seckill.goods.entity.Goods;
import com.seckill.goods.entity.Stock;
import com.seckill.goods.mapper.GoodsMapper;
import com.seckill.goods.mapper.StockMapper;
import com.seckill.goods.vo.GoodsDetailVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoodsServiceImplTest {

    @Mock
    private GoodsMapper goodsMapper;

    @Mock
    private StockMapper stockMapper;

    @InjectMocks
    private GoodsServiceImpl goodsService;

    @Test
    @DisplayName("创建商品：同步初始化 0 库存行")
    void createInitializesStock() {
        GoodsDTO dto = new GoodsDTO();
        dto.setGoodsName("iPhone 16");
        dto.setNormalPrice(new BigDecimal("5999.00"));

        goodsService.create(dto);

        ArgumentCaptor<Stock> captor = ArgumentCaptor.forClass(Stock.class);
        verify(stockMapper).insert(captor.capture());
        Stock stock = captor.getValue();
        assertEquals(0, stock.getTotalStock());
        assertEquals(0, stock.getAvailableStock());
        assertEquals(0, stock.getSoldCount());
    }

    @Test
    @DisplayName("查询详情：商品不存在抛业务异常")
    void detailNotFound() {
        when(goodsMapper.selectById(99L)).thenReturn(null);
        BizException e = assertThrows(BizException.class, () -> goodsService.getDetail(99L));
        assertEquals(ErrorCode.GOODS_NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    @DisplayName("查询详情：商品与库存信息合并返回")
    void detailSuccess() {
        Goods goods = new Goods();
        goods.setId(1L);
        goods.setGoodsName("iPhone 16");
        goods.setNormalPrice(new BigDecimal("5999.00"));
        goods.setStatus(1);

        Stock stock = new Stock();
        stock.setGoodsId(1L);
        stock.setTotalStock(100);
        stock.setAvailableStock(80);
        stock.setSoldCount(20);

        when(goodsMapper.selectById(1L)).thenReturn(goods);
        when(stockMapper.selectOne(any(Wrapper.class))).thenReturn(stock);

        GoodsDetailVO vo = goodsService.getDetail(1L);
        assertEquals("iPhone 16", vo.getGoodsName());
        assertEquals(100, vo.getTotalStock());
        assertEquals(80, vo.getAvailableStock());
        assertEquals(20, vo.getSoldCount());
    }
}
