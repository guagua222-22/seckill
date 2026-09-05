package com.seckill.seckill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.goods.dto.ActivityDTO;
import com.seckill.goods.dto.GoodsDTO;
import com.seckill.goods.entity.Goods;
import com.seckill.goods.entity.SeckillActivity;
import com.seckill.goods.entity.Stock;
import com.seckill.goods.mapper.StockMapper;
import com.seckill.goods.service.GoodsService;
import com.seckill.goods.service.SeckillActivityService;
import com.seckill.order.entity.Order;
import com.seckill.order.mapper.OrderMapper;
import com.seckill.seckill.dto.SeckillOrderDTO;
import com.seckill.seckill.service.SeckillOrderService;
import com.seckill.user.entity.User;
import com.seckill.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Testcontainers
class SeckillOrderIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("seckill_test")
            .withUsername("root")
            .withPassword("root123");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        // 测试使用 Redis db 15，与开发数据（db 0）物理隔离
        registry.add("spring.data.redis.database", () -> 15);
    }

    @Autowired
    private SeckillOrderService seckillOrderService;
    @Autowired
    private GoodsService goodsService;
    @Autowired
    private SeckillActivityService activityService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private StockMapper stockMapper;
    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void cleanRedis() {
        // 每个用例前清空 db 15，避免上一个用例的库存 key 干扰
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    @DisplayName("100 库存：100 人抢光，第 101 人失败，同人重复抢被拦，超卖为 0")
    void fullChainNoOversell() {
        // 准备 101 个用户
        List<Long> userIds = new ArrayList<>();
        for (int i = 1; i <= 101; i++) {
            User user = new User();
            user.setUsername("it_user_" + i);
            user.setPassword("$2a$10$placeholder");
            user.setStatus(1);
            userMapper.insert(user);
            userIds.add(user.getId());
        }

        // 商品 + 进行中的活动（库存 100）
        GoodsDTO goodsDTO = new GoodsDTO();
        goodsDTO.setGoodsName("iPhone 16 Pro");
        goodsDTO.setNormalPrice(new BigDecimal("7999.00"));
        Goods goods = goodsService.create(goodsDTO);

        LocalDateTime now = LocalDateTime.now();
        ActivityDTO activityDTO = new ActivityDTO();
        activityDTO.setActivityName("集成测试活动");
        activityDTO.setGoodsId(goods.getId());
        activityDTO.setSeckillPrice(new BigDecimal("9.90"));
        activityDTO.setTotalStock(100);
        activityDTO.setStartTime(now.minusMinutes(5));
        activityDTO.setEndTime(now.plusHours(1));
        SeckillActivity activity = activityService.create(activityDTO);

        // 前 100 人全部抢到
        for (int i = 0; i < 100; i++) {
            Long orderId = seckillOrderService.createOrder(dto(userIds.get(i), activity.getId(), "req-" + i));
            assertNotNull(orderId, "第 " + i + " 个用户应抢购成功");
        }

        // 第 101 人：库存不足
        BizException e1 = assertThrows(BizException.class,
                () -> seckillOrderService.createOrder(dto(userIds.get(100), activity.getId(), "req-100")));
        assertEquals(ErrorCode.STOCK_NOT_ENOUGH.getCode(), e1.getCode());

        // 已抢到的用户再次抢：一人一单拦截
        BizException e2 = assertThrows(BizException.class,
                () -> seckillOrderService.createOrder(dto(userIds.get(0), activity.getId(), "req-dup")));
        assertEquals(ErrorCode.ALREADY_ORDERED.getCode(), e2.getCode());

        // 数据断言：订单数=100、库存清零、超卖=0
        Long orderCount = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getActivityId, activity.getId()));
        assertEquals(100, orderCount, "订单数应恰好为库存数");

        Stock stock = stockMapper.selectOne(new LambdaQueryWrapper<Stock>()
                .eq(Stock::getGoodsId, goods.getId()));
        assertEquals(0, stock.getAvailableStock(), "可用库存应为 0");
        assertEquals(100, stock.getSoldCount(), "已售应等于总库存");

        // M3 新增：Redis 预扣计数与 DB 库存对账一致（两侧独立扣减，必须同为 0）
        assertEquals(0, seckillOrderService.getRedisStock(activity.getId()),
                "Redis 剩余库存应与 DB 一致为 0");
    }

    private SeckillOrderDTO dto(Long userId, Long activityId, String requestId) {
        SeckillOrderDTO dto = new SeckillOrderDTO();
        dto.setUserId(userId);
        dto.setActivityId(activityId);
        dto.setRequestId(requestId);
        return dto;
    }
}
