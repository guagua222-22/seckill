# 秒杀系统（seckill）

生产级秒杀系统，微服务版技术栈（Spring Cloud Alibaba），按「先单体、后微服务」演进路线施工。

## 当前阶段

M3：Redis 缓存 + Lua 预扣（已完成，含压测对比验收）

## 快速启动

```bash
# 1. 启动中间件（MySQL 8.0.36 映射宿主 3307 + Redis 7.2.4）
docker compose up -d

# 2. 启动应用（JDK 21）
./mvnw spring-boot:run

# 3. 可选：灌入 10 万测试用户（用户名 test_1 ~ test_100000，密码统一 123456）
SEED_USER_COUNT=100000 ./mvnw spring-boot:run

# 4. 验证台前端：浏览器打开 http://localhost:8080/
#    健康检查：curl http://localhost:8080/actuator/health
```

## 已有接口

| 接口 | 说明 |
|---|---|
| POST /api/user/register | 注册（BCrypt 加密，用户名唯一） |
| POST /api/user/login | 登录 |
| POST /api/goods | 创建商品（同步初始化 0 库存行） |
| PUT /api/goods/{id} | 更新商品 |
| GET /api/goods/{id} | 商品详情（含库存） |
| GET /api/goods/page | 商品分页 |
| POST /api/goods/activity | 创建秒杀活动（校验时间窗 + 灌入活动库存） |
| GET /api/goods/activity/{id} | 活动详情 |
| GET /api/goods/activity/page | 活动分页 |
| POST /api/seckill/order | 秒杀下单（M3：Lua 原子预扣 → DB 落单双保险，Redis 故障自动降级 DB 直写） |
| GET /api/seckill/stock/{activityId} | Redis 实时剩余库存（-1 表示未预热） |
| GET /api/order/query?requestId= | 按请求ID查订单 |

统一响应：`{"code":0,"message":"success","data":...}`；业务错误码见 `com.seckill.common.result.ErrorCode`。

### 防超卖与防重复（M2/M3 核心）

- 快闸门：Redis Lua 原子预扣（判断已购→判断库存→扣减→登记四步原子，返回码 1/-1/-2/-3）
- DB 兜底：`UPDATE t_stock SET available_stock = available_stock - 1 WHERE goods_id = ? AND available_stock > 0`，影响 0 行即库存不足
- 一人一单：`uk_user_activity` 唯一索引 + Lua SADD 判重 + DuplicateKeyException 兜底（事务回滚已扣库存）
- 请求幂等：`uk_request_id` 唯一索引
- 预扣回滚：DB 落单失败 → SREM 成功才 INCR 库存（幂等补偿）
- 对账任务：每小时校验 Redis 剩余与 DB 库存，以 DB 为准回写并清理已结束活动 key

### 缓存三件套（M3，商品详情）

| 问题 | 方案 |
|---|---|
| 穿透 | Guava 布隆过滤器（一定不存在直接拒）+ 空值缓存 60s |
| 击穿 | 逻辑过期 + SETNX 互斥重建（过期只放一个请求进 DB，其余返回旧值） |
| 雪崩 | 物理 TTL 30min ± 随机 5min |

库存字段刻意不进详情缓存，永远现查 t_stock。

### 压测结果

**并发正确性（JMeter 5.6.3，1000 并发抢 100 库存）**：订单数=100、DB 库存 0/100、Redis 剩余=0、重复用户=0，**超卖=0**，错误率 0%。

**吞吐对比（oha 1.16，同机测量）**：

| 路径 | QPS | 平均 RT |
|---|---|---|
| 商品详情（缓存命中） | **6122** | 32ms |
| 商品分页（无缓存 DB 读参照） | 1234 | 161ms |
| 秒杀接口（Lua 闸门拒绝路径） | **3159** | 94ms |

> 注：JMeter 同机压测在 ~1000 QPS 处触到客户端自身瓶颈（M2/M3 两版同为 ~199-994/s），
> 吞吐对比改用轻量工具 oha 测量；M2 版秒杀平均 RT 582ms → M3 版 104ms。

## 数据库

Flyway 管理，V1 建 7 张表：t_user / t_goods / t_stock / t_seckill_activity / t_order / t_seckill_record / t_local_message。

关键设计：
- `t_order.uk_user_activity`（一人一单）与 `uk_request_id`（请求幂等）唯一索引
- 库存独立成表 `t_stock`，与商品详情读写分离，避免行锁竞争
- `t_local_message` 为 M4 的可靠消息预留

## 测试

```bash
./mvnw test   # 31 个测试：单测 + Lua 脚本并发测试（200 线程抢 100 库存）+ Testcontainers 全链路集成测试
```

> 注意：
> - Windows 下 Testcontainers 需要 Docker Desktop ≥ 4.44 且使用 testcontainers 2.x（本项目已锁定 2.0.5，兼容 Docker Desktop 29 的 docker_cli 管道）。
> - Lua 脚本测试与集成测试直连本机 Redis 容器的 **db 15**（与开发库 db 0 隔离），需 docker compose 的 redis 在运行。

## 技术栈版本

| 组件 | 版本 |
|---|---|
| Spring Boot | 3.2.5 |
| JDK | 21 |
| MyBatis-Plus | 3.5.7 |
| Flyway | 9.22.3 |
| MySQL / Redis | 8.0.36 / 7.2.4（docker） |
