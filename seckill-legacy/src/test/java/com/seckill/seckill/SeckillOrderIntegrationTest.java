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
import com.seckill.seckill.entity.SeckillRecord;
import com.seckill.seckill.mapper.SeckillRecordMapper;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 全链路集成测试（M4 异步版）。
 * 环境要求：docker compose 全套在跑（MySQL 容器用于 Testcontainers 新实例、Redis db15、RocketMQ）。
 * 隔离设计：测试用独立的 topic 与消费组（seckill-order-topic-it / seckill-order-consumer-it），
 * 避免与开发运行中的应用抢消息或互相污染。
 * 链路：下单（排队中）→ RocketMQ 消费 → 落单 → 轮询断言订单数=100。
 */
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
        // Redis db 15 与开发数据隔离
        registry.add("spring.data.redis.database", () -> 15);
        // MQ 隔离：独立 topic 与消费组
        registry.add("seckill.mq.topic", () -> "seckill-order-topic-it");
        registry.add("seckill.mq.consumer-group", () -> "seckill-order-consumer-it");
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
    private SeckillRecordMapper recordMapper;
    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void cleanRedis() {
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    @DisplayName("异步全链路：100 人排队抢 100 库存，MQ 消费后订单=100、超卖=0")
    void asyncChainNoOversell() throws InterruptedException {
        // 准备 101 个用户
        List<Long> userIds = new ArrayList<>();
        for (int i = 1; i <= 101; i++) {
            User user = new User();
            user.setUsername("it4_user_" + i);
            user.setPassword("$2a$10$placeholder");
            user.setStatus(1);
            userMapper.insert(user);
            userIds.add(user.getId());
        }

        GoodsDTO goodsDTO = new GoodsDTO();
        goodsDTO.setGoodsName("iPhone 16 Pro");
        goodsDTO.setNormalPrice(new BigDecimal("7999.00"));
        Goods goods = goodsService.create(goodsDTO);

        LocalDateTime now = LocalDateTime.now();
        ActivityDTO activityDTO = new ActivityDTO();
        activityDTO.setActivityName("M4 集成测试活动");
        activityDTO.setGoodsId(goods.getId());
        activityDTO.setSeckillPrice(new BigDecimal("9.90"));
        activityDTO.setTotalStock(100);
        activityDTO.setStartTime(now.minusMinutes(5));
        activityDTO.setEndTime(now.plusHours(1));
        SeckillActivity activity = activityService.create(activityDTO);

        // 等待消费组在 broker 完成注册（默认 CONSUME_FROM_LAST_OFFSET，
        // 若消息在注册前到达会被跳过）
        Thread.sleep(5000);

        // 前 100 人排队成功（入口立即返回，无异常即排队成功）
        for (int i = 0; i < 100; i++) {
            seckillOrderService.createOrder(dto(userIds.get(i), activity.getId(), "it-req-" + i));
        }

        // 第 101 人：Lua 库存不足，入口直接拒绝
        BizException e1 = assertThrows(BizException.class,
                () -> seckillOrderService.createOrder(dto(userIds.get(100), activity.getId(), "it-req-100")));
        assertEquals(ErrorCode.STOCK_NOT_ENOUGH.getCode(), e1.getCode());

        // 已排队用户再次抢（换 requestId）：Lua 判重拦截
        BizException e2 = assertThrows(BizException.class,
                () -> seckillOrderService.createOrder(dto(userIds.get(0), activity.getId(), "it-req-dup")));
        assertEquals(ErrorCode.ALREADY_ORDERED.getCode(), e2.getCode());

        // 轮询等待 MQ 消费完成（最长 60 秒，全量测试时 JVM 负载高需要更多时间）
        long deadline = System.currentTimeMillis() + 60_000;
        long orderCount = 0;
        while (System.currentTimeMillis() < deadline) {
            orderCount = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                    .eq(Order::getActivityId, activity.getId()));
            if (orderCount == 100) {
                break;
            }
            Thread.sleep(500);
        }
        assertEquals(100, orderCount, "MQ 消费完成后订单数应恰好为库存数");

        // 库存与流水对账
        Stock stock = stockMapper.selectOne(new LambdaQueryWrapper<Stock>()
                .eq(Stock::getGoodsId, goods.getId()));
        assertEquals(0, stock.getAvailableStock(), "可用库存应为 0");
        assertEquals(100, stock.getSoldCount(), "已售应等于总库存");
        assertEquals(0, seckillOrderService.getRedisStock(activity.getId()),
                "Redis 剩余库存应与 DB 一致为 0");

        Long doneRecords = recordMapper.selectCount(new LambdaQueryWrapper<SeckillRecord>()
                .eq(SeckillRecord::getActivityId, activity.getId())
                .eq(SeckillRecord::getStatus, 1));
        assertEquals(100, doneRecords, "流水状态应全部推进到已下单");
    }

    private SeckillOrderDTO dto(Long userId, Long activityId, String requestId) {
        SeckillOrderDTO dto = new SeckillOrderDTO();
        dto.setUserId(userId);
        dto.setActivityId(activityId);
        dto.setRequestId(requestId);
        return dto;
    }
}
