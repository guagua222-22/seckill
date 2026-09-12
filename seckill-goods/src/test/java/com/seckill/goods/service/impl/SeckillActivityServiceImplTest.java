package com.seckill.goods.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.goods.dto.ActivityDTO;
import com.seckill.goods.entity.Goods;
import com.seckill.goods.entity.SeckillActivity;
import com.seckill.goods.entity.Stock;
import com.seckill.goods.mapper.GoodsMapper;
import com.seckill.goods.mapper.SeckillActivityMapper;
import com.seckill.goods.mapper.StockMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillActivityServiceImplTest {

    @Mock
    private SeckillActivityMapper activityMapper;

    @Mock
    private GoodsMapper goodsMapper;

    @Mock
    private StockMapper stockMapper;

    @InjectMocks
    private SeckillActivityServiceImpl activityService;

    private ActivityDTO buildDTO(LocalDateTime start, LocalDateTime end) {
        ActivityDTO dto = new ActivityDTO();
        dto.setActivityName("双11秒杀");
        dto.setGoodsId(1L);
        dto.setSeckillPrice(new BigDecimal("9.90"));
        dto.setTotalStock(100);
        dto.setStartTime(start);
        dto.setEndTime(end);
        return dto;
    }

    @Test
    @DisplayName("创建活动成功：未来时间窗状态为未开始，库存被灌入")
    void createSuccess() {
        Goods goods = new Goods();
        goods.setId(1L);
        goods.setGoodsName("iPhone 16");
        when(goodsMapper.selectById(1L)).thenReturn(goods);

        Stock stock = new Stock();
        stock.setId(10L);
        stock.setGoodsId(1L);
        when(stockMapper.selectOne(any(Wrapper.class))).thenReturn(stock);

        LocalDateTime now = LocalDateTime.now();
        SeckillActivity activity = activityService.create(
                buildDTO(now.plusDays(1), now.plusDays(2)));

        assertEquals(0, activity.getStatus());
        assertEquals(100, stock.getTotalStock());
        assertEquals(100, stock.getAvailableStock());
        // 活动行带商品名快照：查活动表时不用联 t_goods 就知道卖的是什么
        assertEquals("iPhone 16", activity.getGoodsName());
        verify(stockMapper).updateById(stock);
    }

    @Test
    @DisplayName("创建活动失败：商品不存在")
    void createGoodsNotFound() {
        when(goodsMapper.selectById(1L)).thenReturn(null);
        LocalDateTime now = LocalDateTime.now();
        BizException e = assertThrows(BizException.class,
                () -> activityService.create(buildDTO(now.plusDays(1), now.plusDays(2))));
        assertEquals(ErrorCode.GOODS_NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    @DisplayName("创建活动失败：结束时间不晚于开始时间")
    void createTimeInvalid() {
        Goods goods = new Goods();
        goods.setId(1L);
        when(goodsMapper.selectById(1L)).thenReturn(goods);

        LocalDateTime now = LocalDateTime.now();
        BizException e = assertThrows(BizException.class,
                () -> activityService.create(buildDTO(now.plusDays(2), now.plusDays(1))));
        assertEquals(ErrorCode.ACTIVITY_TIME_INVALID.getCode(), e.getCode());
    }

    @Test
    @DisplayName("创建活动：库存行不存在时新建")
    void createInsertsStockWhenAbsent() {
        Goods goods = new Goods();
        goods.setId(1L);
        goods.setGoodsName("iPhone 16");
        when(goodsMapper.selectById(1L)).thenReturn(goods);
        when(stockMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        LocalDateTime now = LocalDateTime.now();
        activityService.create(buildDTO(now.plusDays(1), now.plusDays(2)));

        ArgumentCaptor<Stock> captor = ArgumentCaptor.forClass(Stock.class);
        verify(stockMapper).insert(captor.capture());
        assertEquals(100, captor.getValue().getAvailableStock());
        assertEquals("iPhone 16", captor.getValue().getGoodsName());
    }
}
