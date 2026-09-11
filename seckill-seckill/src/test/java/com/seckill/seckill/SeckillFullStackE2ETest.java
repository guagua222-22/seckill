package com.seckill.seckill;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全栈 e2e 测试（M5 微服务版，替代原单体 SeckillOrderIntegrationTest）。
 *
 * 与旧集成测试的区别：
 * - 零 Spring 上下文、零 Testcontainers：纯 JDK HttpClient 直连【网关 8080】，
 *   走的是"网关 → Nacos 服务发现 → 各服务 → 三库"的真实生产路径
 * - 前置要求：docker compose 全套中间件（mysql/redis/rocketmq/nacos）
 *   + user/goods/seckill/gateway 四个应用全部在跑；探测失败则跳过（Assumptions.abort）
 * - 测试数据全部带时间戳后缀，finally 里按后缀清理三库
 *
 * 运行方式：./mvnw -pl seckill-seckill -Pe2e test
 * （默认构建通过 surefire excludedGroups=e2e 排除本测试）
 */
@Tag("e2e")
class SeckillFullStackE2ETest {

    private static final String GATEWAY = "http://localhost:8080";
    private static final String JDBC_URL = "jdbc:mysql://localhost:3307";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    /** 测试数据统一后缀，用于清理与防串扰 */
    private static final String SUFFIX = "e2e" + System.currentTimeMillis();

    private static String goodsId;
    private static String activityId;
    private static final List<String> userIds = new ArrayList<>();
    private static final List<String> requestIds = new ArrayList<>();

    @BeforeAll
    static void probeEnvironment() throws Exception {
        // 环境探测：网关不通则跳过（而非失败），本地未起全套时不影响日常构建
        try {
            HttpResponse<String> health = HTTP.send(
                    HttpRequest.newBuilder(URI.create(GATEWAY + "/actuator/health")).build(),
                    HttpResponse.BodyHandlers.ofString());
            Assumptions.assumeTrue(health.statusCode() == 200, "网关未运行，跳过 e2e");
        } catch (Exception e) {
            Assumptions.abort("环境未就绪，跳过 e2e: " + e.getMessage());
        }
        // 顺带验证静态页路由
        HttpResponse<String> page = HTTP.send(
                HttpRequest.newBuilder(URI.create(GATEWAY + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
        Assumptions.assumeTrue(page.body().contains("秒杀"), "前端页未就绪，跳过 e2e");
    }

    @Test
    @DisplayName("全栈 100 人抢 100 库存：网关→服务→MQ→三库，超卖=0")
    void fullStackSeckill() throws Exception {
        // 1. 经网关注册 101 个用户（第 101 个用于验证库存不足）
        for (int i = 1; i <= 101; i++) {
            String body = String.format("{\"username\":\"%s_u%d\",\"password\":\"123456\"}", SUFFIX, i);
            HttpResponse<String> resp = post("/api/user/register", body);
            assertEquals(0, code(resp), "注册第" + i + "个用户应成功: " + resp.body());
            userIds.add(dataId(resp));
        }

        // 2. 经网关建商品 + 活动（立即开始，100 库存）
        HttpResponse<String> goodsResp = post("/api/goods",
                String.format("{\"goodsName\":\"E2EGoods_%s\",\"normalPrice\":99.00}", SUFFIX));
        assertEquals(0, code(goodsResp), "建商品应成功");
        goodsId = dataId(goodsResp);

        HttpResponse<String> actResp = post("/api/goods/activity", String.format(
                "{\"activityName\":\"E2EActivity_%s\",\"goodsId\":%s,\"seckillPrice\":9.90," +
                "\"totalStock\":100,\"startTime\":\"2026-09-11T00:00:00\",\"endTime\":\"2026-09-12T00:00:00\"}",
                SUFFIX, goodsId));
        assertEquals(0, code(actResp), "建活动应成功: " + actResp.body());
        activityId = dataId(actResp);

        // 3. 轮询等待预热就位（goods 预热 + seckill 读 Redis 的跨服务契约验证）
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            String stock = get("/api/seckill/stock/" + activityId).body();
            if (stock.contains("\"data\":100")) {
                break;
            }
            Thread.sleep(500);
        }
        assertTrue(get("/api/seckill/stock/" + activityId).body().contains("\"data\":100"),
                "预热后 Redis 库存应为 100");

        // 4. 100 线程齐发抢购（经网关）
        int concurrent = 100;
        ExecutorService pool = Executors.newFixedThreadPool(concurrent);
        CountDownLatch ready = new CountDownLatch(concurrent);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < concurrent; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                String reqId = SUFFIX + "-req-" + idx;
                requestIds.add(reqId);
                ready.countDown();
                go.await();
                return post("/api/seckill/order", String.format(
                        "{\"userId\":%s,\"activityId\":%s,\"requestId\":\"%s\"}",
                        userIds.get(idx), activityId, reqId));
            }));
        }
        ready.await();
        go.countDown();
        for (int i = 0; i < concurrent; i++) {
            assertEquals(0, code(futures.get(i).get()), "第" + i + "个用户排队应成功");
        }
        pool.shutdown();

        // 5. 第 101 人：库存不足 2004；已抢用户换 requestId：已抢过 3002
        HttpResponse<String> noStock = post("/api/seckill/order", String.format(
                "{\"userId\":%s,\"activityId\":%s,\"requestId\":\"%s-req-101\"}",
                userIds.get(100), activityId, SUFFIX));
        assertEquals(2004, code(noStock), "第 101 人应库存不足");
        HttpResponse<String> dup = post("/api/seckill/order", String.format(
                "{\"userId\":%s,\"activityId\":%s,\"requestId\":\"%s-req-dup\"}",
                userIds.get(0), activityId, SUFFIX));
        assertEquals(3002, code(dup), "已抢用户应被拦截");

        // 6. 轮询 60s：100 个 requestId 全部查单成功（MQ 异步消费）
        deadline = System.currentTimeMillis() + 60_000;
        int done = 0;
        while (System.currentTimeMillis() < deadline && done < concurrent) {
            done = 0;
            for (String reqId : requestIds) {
                if (get("/api/order/query?requestId=" + reqId).body().contains("\"code\":0")) {
                    done++;
                }
            }
            if (done < concurrent) {
                Thread.sleep(500);
            }
        }
        assertEquals(concurrent, done, "60 秒内 100 单应全部消费落单");

        // 7. 三库直连断言（JDBC）：流水/库存/扣减流水与订单数一致
        try (Connection conn = DriverManager.getConnection(
                JDBC_URL + "/seckill_seckill?useSSL=false&allowPublicKeyRetrieval=true", "root", "root123");
             Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM t_order WHERE activity_id=" + activityId);
            rs.next();
            assertEquals(100, rs.getInt(1), "订单数应为 100");
            rs = st.executeQuery(
                    "SELECT COUNT(*) FROM t_seckill_record WHERE activity_id=" + activityId + " AND status=1");
            rs.next();
            assertEquals(100, rs.getInt(1), "流水应 100 条全部已下单");
        }
        try (Connection conn = DriverManager.getConnection(
                JDBC_URL + "/seckill_goods?useSSL=false&allowPublicKeyRetrieval=true", "root", "root123");
             Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery(
                    "SELECT available_stock, sold_count FROM t_stock WHERE goods_id=" + goodsId);
            rs.next();
            assertEquals(0, rs.getInt(1), "可用库存应为 0（超卖=0）");
            assertEquals(100, rs.getInt(2), "已售应等于总库存");
            rs = st.executeQuery(
                    "SELECT COUNT(*) FROM t_stock_operation WHERE activity_id=" + activityId);
            rs.next();
            assertEquals(100, rs.getInt(1), "扣减流水应 100 条（幂等依据完整）");
        }

        // 8. Redis 剩余库存与 DB 一致为 0
        assertTrue(get("/api/seckill/stock/" + activityId).body().contains("\"data\":0"),
                "Redis 剩余库存应与 DB 一致为 0");
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (activityId == null) {
            return;
        }
        // 先删引用方（订单/流水/消息/扣减流水），再删被引用方（活动/库存/商品/用户）
        try (Connection conn = DriverManager.getConnection(
                JDBC_URL + "/seckill_seckill?useSSL=false&allowPublicKeyRetrieval=true", "root", "root123");
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM t_local_message WHERE message_id LIKE '" + SUFFIX + "%'");
            st.execute("DELETE FROM t_order WHERE request_id LIKE '" + SUFFIX + "%'");
            st.execute("DELETE FROM t_seckill_record WHERE request_id LIKE '" + SUFFIX + "%'");
        }
        try (Connection conn = DriverManager.getConnection(
                JDBC_URL + "/seckill_goods?useSSL=false&allowPublicKeyRetrieval=true", "root", "root123");
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM t_stock_operation WHERE request_id LIKE '" + SUFFIX + "%'");
            st.execute("DELETE FROM t_stock WHERE goods_id=" + goodsId);
            st.execute("DELETE FROM t_seckill_activity WHERE activity_name LIKE 'E2EActivity_" + SUFFIX + "%'");
            st.execute("DELETE FROM t_goods WHERE goods_name LIKE 'E2EGoods_" + SUFFIX + "%'");
        }
        try (Connection conn = DriverManager.getConnection(
                JDBC_URL + "/seckill_user?useSSL=false&allowPublicKeyRetrieval=true", "root", "root123");
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM t_user WHERE username LIKE '" + SUFFIX + "%'");
        }
    }

    private static HttpResponse<String> post(String path, String json) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(GATEWAY + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(GATEWAY + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int code(HttpResponse<String> resp) {
        String body = resp.body();
        int start = body.indexOf("\"code\":");
        if (start < 0) {
            return -1;
        }
        int end = body.indexOf(',', start);
        return Integer.parseInt(body.substring(start + 7, end));
    }

    private static String dataId(HttpResponse<String> resp) {
        String body = resp.body();
        int start = body.indexOf("\"id\":\"");
        if (start < 0) {
            return "";
        }
        int end = body.indexOf('"', start + 6);
        return body.substring(start + 6, end);
    }
}
