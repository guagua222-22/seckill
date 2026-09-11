package com.seckill.seckill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lua 预扣脚本语义测试。
 * 直连本机 Redis 容器（docker compose 的 redis，使用 db 15 隔离，测试间 flushdb 清理，不污染开发库）。
 * 核心验证：脚本四步的原子性——并发下"判断+扣减+登记"不会被交错执行，
 * 200 线程抢 100 库存必须恰好 100 成功、0 超卖。
 */
class SeckillPreDeductScriptTest {

    private static final String STOCK_KEY = "seckill:stock:1";
    private static final String USER_SET_KEY = "seckill:user:set:1";

    private static final StringRedisTemplate redis = buildRedis();

    private DefaultRedisScript<Long> script;

    /** 手工构建连到 db 15 的模板：测试数据与开发数据物理隔离 */
    private static StringRedisTemplate buildRedis() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", 6379);
        factory.setDatabase(15);
        factory.afterPropertiesSet();
        return new StringRedisTemplate(factory);
    }

    @BeforeEach
    void setUp() {
        // 每个用例前清空 db 15，保证用例间互不影响
        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/seckill_pre_deduct.lua"));
        script.setResultType(Long.class);
    }

    private Long execute(Long userId) {
        return redis.execute(script, List.of(STOCK_KEY, USER_SET_KEY), String.valueOf(userId));
    }

    @Test
    @DisplayName("未预热（key 不存在）返回 -3，防穿透到 DB")
    void notPreheated() {
        assertEquals(-3L, execute(1L));
    }

    @Test
    @DisplayName("扣减成功 + 重复购买拦截")
    void deductAndDuplicate() {
        redis.opsForValue().set(STOCK_KEY, "10");
        assertEquals(1L, execute(1L));
        assertEquals("9", redis.opsForValue().get(STOCK_KEY));
        assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(USER_SET_KEY, "1")));
        // 同一用户再次抢：Lua 里 SISMEMBER 判重拦截
        assertEquals(-1L, execute(1L));
        // 库存没有被多扣
        assertEquals("9", redis.opsForValue().get(STOCK_KEY));
    }

    @Test
    @DisplayName("库存为 0 返回 -2")
    void stockEmpty() {
        redis.opsForValue().set(STOCK_KEY, "0");
        assertEquals(-2L, execute(1L));
    }

    @Test
    @DisplayName("并发原子性：200 线程抢 100 库存，恰好 100 成功")
    void concurrentAtomicity() throws InterruptedException {
        redis.opsForValue().set(STOCK_KEY, "100");

        int threads = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<Long> results = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            final long userId = i + 1;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    results.add(execute(userId));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        ready.await();
        go.countDown(); // 同时放行，制造最大并发
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        Map<Long, Long> counts = results.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertEquals(100L, counts.getOrDefault(1L, 0L), "恰好 100 个成功");
        assertEquals(100L, counts.getOrDefault(-2L, 0L), "其余 100 个全部库存不足");
        assertEquals("0", redis.opsForValue().get(STOCK_KEY), "库存精确扣到 0，超卖=0");
    }
}
