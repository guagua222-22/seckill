# 秒杀系统（seckill）

生产级秒杀系统，微服务版技术栈（Spring Cloud Alibaba），按「先单体、后微服务」演进路线施工。

## 当前阶段

M1：数据库设计与基础 CRUD（已完成）

## 快速启动

```bash
# 1. 启动中间件（MySQL 8.0.36 映射宿主 3307 + Redis 7.2.4）
docker compose up -d

# 2. 启动应用（JDK 21）
./mvnw spring-boot:run

# 3. 可选：灌入 10 万测试用户（用户名 test_1 ~ test_100000，密码统一 123456）
SEED_USER_COUNT=100000 ./mvnw spring-boot:run

# 4. 健康检查
curl http://localhost:8080/actuator/health
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

统一响应：`{"code":0,"message":"success","data":...}`；业务错误码见 `com.seckill.common.result.ErrorCode`。

## 数据库

Flyway 管理，V1 建 7 张表：t_user / t_goods / t_stock / t_seckill_activity / t_order / t_seckill_record / t_local_message。

关键设计：
- `t_order.uk_user_activity`（一人一单）与 `uk_request_id`（请求幂等）唯一索引
- 库存独立成表 `t_stock`，与商品详情读写分离，避免行锁竞争
- `t_local_message` 为 M4 的可靠消息预留

## 测试

```bash
./mvnw test   # 12 个 Service 层单测 + jacoco 覆盖率检查（service 层 ≥ 60%）
```

## 技术栈版本

| 组件 | 版本 |
|---|---|
| Spring Boot | 3.2.5 |
| JDK | 21 |
| MyBatis-Plus | 3.5.7 |
| Flyway | 9.22.3 |
| MySQL / Redis | 8.0.36 / 7.2.4（docker） |
