# 秒杀系统（seckill）

生产级秒杀系统，微服务版技术栈（Spring Cloud Alibaba），按「先单体、后微服务」演进路线施工。

## 当前阶段

M2：秒杀核心链路 DB 版（已完成，含并发正确性压测验收）

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
| POST /api/seckill/order | 秒杀下单（时间窗校验→一人一单→条件更新扣库存→落订单） |
| GET /api/order/query?requestId= | 按请求ID查订单 |

统一响应：`{"code":0,"message":"success","data":...}`；业务错误码见 `com.seckill.common.result.ErrorCode`。

### 防超卖与防重复（M2 核心）

- 扣库存用条件更新：`UPDATE t_stock SET available_stock = available_stock - 1 WHERE goods_id = ? AND available_stock > 0`，影响 0 行即库存不足
- 一人一单：`uk_user_activity` 唯一索引 + 预检 + DuplicateKeyException 兜底（事务回滚已扣库存）
- 请求幂等：`uk_request_id` 唯一索引

### M2 压测结果（JMeter 5.6.3，本机）

- 1000 并发抢 100 库存：订单数=100、可用库存=0、已售=100、重复用户=0，**超卖=0**，错误率 0%，QPS≈199，平均 RT 582ms
- 同用户 10 并发：1 单成功、9 次被"已抢过"拦截
- 时间窗外请求：未开始 2005 / 已结束 2006

## 数据库

Flyway 管理，V1 建 7 张表：t_user / t_goods / t_stock / t_seckill_activity / t_order / t_seckill_record / t_local_message。

关键设计：
- `t_order.uk_user_activity`（一人一单）与 `uk_request_id`（请求幂等）唯一索引
- 库存独立成表 `t_stock`，与商品详情读写分离，避免行锁竞争
- `t_local_message` 为 M4 的可靠消息预留

## 测试

```bash
./mvnw test   # 18 个单测 + Testcontainers 集成测试（真实 MySQL 容器跑全链路）+ jacoco 覆盖率检查
```

> 注意：Windows 下 Testcontainers 需要 Docker Desktop ≥ 4.44 且使用 testcontainers 2.x（本项目已锁定 2.0.5，兼容 Docker Desktop 29 的 docker_cli 管道）。

## 技术栈版本

| 组件 | 版本 |
|---|---|
| Spring Boot | 3.2.5 |
| JDK | 21 |
| MyBatis-Plus | 3.5.7 |
| Flyway | 9.22.3 |
| MySQL / Redis | 8.0.36 / 7.2.4（docker） |
