# 秒杀系统（seckill）

生产级秒杀系统，微服务架构（Spring Cloud Alibaba）。按「先单体、后微服务」路线演进：M1-M4 以单体跑通核心链路，M5 完成微服务拆分。

## 当前阶段

M5：微服务拆分（已完成）——Nacos 注册中心 + Gateway 网关 + OpenFeign 跨服务调用 + 每服务独立数据库

## 架构总览

```
                     ┌──────────────┐
   浏览器 :8080 ────► │   Gateway    │──┬─ /api/user/** ───► user-service   :8081 ─► MySQL seckill_user
   （验证台静态页）   │  (webflux)   │  ├─ /api/goods/** ──► goods-service  :8082 ─► MySQL seckill_goods
                     └──────┬───────┘  └─ /api/seckill/** ─► seckill-service :8083 ─► MySQL seckill_seckill
                            │              /api/order/**      （含订单域+前端页）
                     ┌──────▼───────┐
                     │ Nacos :8848  │  服务注册/发现；Feign 走 lb:// 直连（不经网关）
                     └──────┬───────┘
                     ┌──────▼───────────────────────────────────────┐
                     │ Redis :6379（共享：Lua 预扣/活动缓存/预热契约）  │
                     │ RocketMQ :9876（只有 seckill-service 用）      │
                     └──────────────────────────────────────────────┘
```

| 模块 | 端口 | 数据库 | 职责 |
|---|---|---|---|
| seckill-common | - | - | 统一返回/错误码/异常/Jackson/MyBatis 配置/Feign 契约 DTO/Redis key 契约 |
| seckill-gateway | 8080 | - | 统一入口：路由分发 + 前端静态页转发（webflux，禁引 common） |
| seckill-user | 8081 | seckill_user | 注册/登录（BCrypt）+ 内部用户存在性接口 |
| seckill-goods | 8082 | seckill_goods | 商品/活动 CRUD、缓存三件套、预热/对账、内部库存扣减（幂等流水） |
| seckill-seckill | 8083 | seckill_seckill | Lua 预扣、MQ 异步落单、本地消息表、订单域、前端验证台 |

**跨服务铁律**：只能走对方 service/controller 接口（Feign），禁止跨服务访问 mapper/数据库。
内部接口前缀 `/internal`，网关不路由（外部不可达），Feign 走 `lb://` 直连。

## 快速启动

```bash
# 1. 启动中间件（MySQL 8.0.36 :3307 + Redis + RocketMQ 4.9.4 + Nacos 2.3.2）
docker compose up -d

# 2. 启动 4 个应用（各开一个终端，或 IDEA 里跑 4 个启动类）
./mvnw -pl seckill-user spring-boot:run
./mvnw -pl seckill-goods spring-boot:run
./mvnw -pl seckill-seckill spring-boot:run
./mvnw -pl seckill-gateway spring-boot:run

# 3. 浏览器打开 http://localhost:8080/（验证台前端）
#    Nacos 控制台 http://localhost:8848/nacos（查看服务注册）
#    健康检查 curl http://localhost:8080/actuator/health
```

> 首次拆分（从旧单体升级）需一次性执行建库与数据迁移，见 `scripts/db/`。

## 接口清单（全部经网关 8080）

| 接口 | 服务 | 说明 |
|---|---|---|
| POST /api/user/register | user | 注册（BCrypt，用户名唯一） |
| POST /api/user/login | user | 登录 |
| POST /api/goods | goods | 创建商品 |
| GET /api/goods/{id} | goods | 商品详情（缓存三件套） |
| GET /api/goods/page | goods | 商品分页 |
| POST /api/goods/activity | goods | 创建秒杀活动（灌库存+预热） |
| GET /api/goods/activity/page | goods | 活动分页 |
| POST /api/seckill/order | seckill | 秒杀下单（Lua 预扣→本地消息表→MQ 异步落单） |
| GET /api/seckill/stock/{activityId} | seckill | Redis 实时剩余库存 |
| GET /api/order/query?requestId= | seckill | 按请求 ID 查单 |

统一响应 `{"code":0,"message":"success","data":...}`；错误码见 `seckill-common` 的 `ErrorCode`（跨服务原码透传）。

## 核心链路与防超卖（M2-M4 沉淀，M5 跨服务化）

1. **快闸门**：Redis Lua 原子预扣（判重→判库存→扣减→登记，返回 1/-1/-2/-3）
2. **可靠消息**：流水 + 本地消息表同事务落库 → asyncSend → 失败由 `MessageResendJob`（30s）补偿
3. **消费幂等三层**：SETNX 去重（命中后查流水状态，排队中重入处理）→ Redisson 用户锁 → DB 唯一索引
4. **跨库补偿**（M5 新增）：`t_stock_operation` 幂等流水——扣库存与流水同事务、requestId 唯一键，
   Feign 重试/MQ 重投不重复扣减；先扣库存后插单，冲突同步补偿 + 对账兜底
5. **对账任务**：`StockReconcileJob`（库存对账）、`RecordReconcileJob`（流水对账，含 DB 库存补偿）
6. **降级**：Redis 整体不可用 → DB 同步直写（条件更新 + 唯一索引兜底）

### 缓存三件套（goods-service，商品详情）

穿透（Guava 布隆 + 空值缓存）/ 击穿（逻辑过期 + SETNX 互斥重建）/ 雪崩（TTL 30min ± 随机）。
> 注：布隆为单实例本地版，多实例部署前须换 Redisson 分布式布隆（M6 与多实例压测一起做）。

## 数据库

三个库同实例（MySQL :3307），各服务 Flyway 各自管理建表：

| 库 | 表 |
|---|---|
| seckill_user | t_user |
| seckill_goods | t_goods / t_stock / t_seckill_activity / t_stock_operation（幂等扣减流水） |
| seckill_seckill | t_order / t_seckill_record / t_local_message |

关键设计：`uk_user_activity`（一人一单）、`uk_request_id`（请求幂等）、`t_stock_operation.uk_request`（跨服务补偿幂等）、库存与商品分表（热点行隔离）。

`scripts/db/`：`init-databases.sql`（建三库）、`migrate-data.sql`（旧 seckill 库数据一次性迁移，可重复执行）。

## 测试

```bash
./mvnw clean verify        # 全模块单测 + jacoco（e2e 默认排除）
./mvnw -pl seckill-seckill -Pe2e test   # 全栈 e2e：100 并发抢 100 库存（需全套环境在跑）
```

- 单测：user 5 + goods 19 + seckill 33（Lua 并发脚本测试直连 Redis db15，需 redis 容器在跑）
- e2e：JDK HttpClient 直连网关，走"网关→Nacos→服务→MQ→三库"真实路径；环境未起自动跳过
- 覆盖率：三业务服务 service 层 LINE ≥ 60%

## 技术栈版本

| 组件 | 版本 |
|---|---|
| Spring Boot / JDK | 3.2.5 / 21 |
| Spring Cloud / Alibaba | 2023.0.2 / 2023.0.1.0（Nacos 2.3.2） |
| MyBatis-Plus / Flyway | 3.5.7 / 9.22.3 |
| MySQL / Redis | 8.0.36 / 7.2.4（docker） |
| RocketMQ | 4.9.4（broker）+ starter 2.3.2（client 钉 5.3.1） |
| Redisson / Guava | 3.32.0 / 33.3.1-jre |

### 环境踩坑记录

- **RocketMQ 端口**：10909/10911 落在 Windows WinNAT 保留段 10885-10984，broker 监听端口改 10996（客户端经 namesrv 自动获取）
- **RocketMQ client 版本**：SCA BOM 会把 rocketmq-client 压到 5.1.4（与 starter 2.3.2 不兼容），根 pom 显式钉 5.3.1
- **Feign + loadbalancer**：SC 2023 必须显式引入 loadbalancer，否则 `lb://` 报 No LoadBalancerClient
- **gateway 依赖纪律**：严禁引 starter-web/seckill-common（webflux 冲突启动即挂）
- **Nacos**：9848 gRPC 端口必须映射；WSL2 内存预算内堆压到 256m
- **跨服务时间/Long 对称**：三服务 `spring.jackson.*` 逐字一致 + common 共享 JacksonConfig
