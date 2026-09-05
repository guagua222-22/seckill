package com.seckill.seckill.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.goods.entity.Goods;
import com.seckill.goods.entity.SeckillActivity;
import com.seckill.goods.mapper.GoodsMapper;
import com.seckill.goods.mapper.SeckillActivityMapper;
import com.seckill.goods.mapper.StockMapper;
import com.seckill.order.entity.Order;
import com.seckill.order.mapper.OrderMapper;
import com.seckill.seckill.dto.SeckillOrderDTO;
import com.seckill.user.entity.User;
import com.seckill.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeckillOrderServiceImplTest {

    @Mock
    private SeckillActivityMapper activityMapper;
    @Mock
    private GoodsMapper goodsMapper;
    @Mock
    private StockMapper stockMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private SeckillOrderServiceImpl seckillOrderService;

    private SeckillActivity activity;
    private Goods goods;
    private User user;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();
        activity = new SeckillActivity();
        activity.setId(100L);
        activity.setGoodsId(1L);
        activity.setSeckillPrice(new BigDecimal("9.90"));
        activity.setStartTime(now.minusHours(1));
        activity.setEndTime(now.plusHours(1));

        goods = new Goods();
        goods.setId(1L);
        goods.setGoodsName("iPhone 16 Pro");

        user = new User();
        user.setId(1L);

        when(activityMapper.selectById(100L)).thenReturn(activity);
        when(userMapper.selectById(1L)).thenReturn(user);
        when(goodsMapper.selectById(1L)).thenReturn(goods);
        when(orderMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(stockMapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
    }

    private SeckillOrderDTO dto() {
        SeckillOrderDTO dto = new SeckillOrderDTO();
        dto.setUserId(1L);
        dto.setActivityId(100L);
        dto.setRequestId("req-1");
        return dto;
    }

    @Test
    @DisplayName("活动未开始：拒绝下单")
    void activityNotStarted() {
        activity.setStartTime(LocalDateTime.now().plusHours(1));
        activity.setEndTime(LocalDateTime.now().plusHours(2));
        BizException e = assertThrows(BizException.class, () -> seckillOrderService.createOrder(dto()));
        assertEquals(ErrorCode.ACTIVITY_NOT_STARTED.getCode(), e.getCode());
    }

    @Test
    @DisplayName("活动已结束：拒绝下单")
    void activityEnded() {
        activity.setStartTime(LocalDateTime.now().minusHours(2));
        activity.setEndTime(LocalDateTime.now().minusHours(1));
        BizException e = assertThrows(BizException.class, () -> seckillOrderService.createOrder(dto()));
        assertEquals(ErrorCode.ACTIVITY_ENDED.getCode(), e.getCode());
    }

    @Test
    @DisplayName("已抢过：预检拦截，不扣库存")
    void alreadyOrdered() {
        when(orderMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        BizException e = assertThrows(BizException.class, () -> seckillOrderService.createOrder(dto()));
        assertEquals(ErrorCode.ALREADY_ORDERED.getCode(), e.getCode());
        verify(stockMapper, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    @DisplayName("库存不足：条件更新影响 0 行")
    void stockNotEnough() {
        when(stockMapper.update(isNull(), any(Wrapper.class))).thenReturn(0);
        BizException e = assertThrows(BizException.class, () -> seckillOrderService.createOrder(dto()));
        assertEquals(ErrorCode.STOCK_NOT_ENOUGH.getCode(), e.getCode());
    }

    @Test
    @DisplayName("下单成功：扣库存并落订单（含快照）")
    void createOrderSuccess() {
        // 模拟 MP 的 ASSIGN_ID 回填主键
        doAnswer(inv -> {
            inv.getArgument(0, Order.class).setId(123L);
            return 1;
        }).when(orderMapper).insert(any(Order.class));

        Long orderId = seckillOrderService.createOrder(dto());
        assertEquals(123L, orderId);

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(captor.capture());
        Order order = captor.getValue();
        assertEquals(1L, order.getUserId());
        assertEquals(100L, order.getActivityId());
        assertEquals("iPhone 16 Pro", order.getGoodsName());
        assertEquals(0, order.getStatus());
        assertEquals("req-1", order.getRequestId());
        assertNotNull(order.getOrderNo());
    }

    @Test
    @DisplayName("并发窗口兜底：插入撞唯一索引→已抢过")
    void duplicateKeyRollback() {
        when(orderMapper.insert(any(Order.class))).thenThrow(new DuplicateKeyException("uk_user_activity"));
        BizException e = assertThrows(BizException.class, () -> seckillOrderService.createOrder(dto()));
        assertEquals(ErrorCode.ALREADY_ORDERED.getCode(), e.getCode());
    }
}
