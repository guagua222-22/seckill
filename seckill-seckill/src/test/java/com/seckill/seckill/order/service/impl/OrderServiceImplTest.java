package com.seckill.seckill.order.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.seckill.order.entity.Order;
import com.seckill.seckill.order.mapper.OrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单查询单测（order 域并入 seckill-service 后补的覆盖率）。
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderMapper orderMapper;

    @InjectMocks
    private OrderServiceImpl orderService;

    @BeforeAll
    static void initLambdaCache() {
        // 纯单测无 Spring 上下文，LambdaQueryWrapper 需要手动初始化实体元信息缓存
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Order.class);
    }

    @Test
    @DisplayName("按 requestId 查单：命中返回订单")
    void queryHit() {
        Order order = new Order();
        order.setRequestId("req-1");
        order.setOrderNo("no-1");
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(order);

        Order result = orderService.getByRequestId("req-1");
        assertEquals("no-1", result.getOrderNo());
    }

    @Test
    @DisplayName("按 requestId 查单：未命中抛订单不存在")
    void queryMiss() {
        when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> orderService.getByRequestId("req-1"));
        assertEquals(ErrorCode.ORDER_NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    @DisplayName("最近订单列表：倒序取 limit 条，返回含用户名/活动名快照")
    void listRecentReturnsOrders() {
        Order order = new Order();
        order.setOrderNo("no-1");
        order.setUsername("test_1");
        order.setActivityName("测试活动");
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(order));

        List<Order> result = orderService.listRecent(20);

        assertEquals(1, result.size());
        assertEquals("test_1", result.get(0).getUsername());
        assertEquals("测试活动", result.get(0).getActivityName());
    }

    @Test
    @DisplayName("limit 上下界保护：0 夹到 1，超大值夹到 200（防整表拉取）")
    void listRecentClampsLimit() {
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        orderService.listRecent(0);
        orderService.listRecent(99999);

        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(orderMapper, org.mockito.Mockito.times(2)).selectList(captor.capture());
        assertTrue(captor.getAllValues().get(0).getSqlSegment().contains("LIMIT 1"));
        assertTrue(captor.getAllValues().get(1).getSqlSegment().contains("LIMIT 200"));
    }
}
