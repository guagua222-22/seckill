# 秒杀系统（seckill）

生产级秒杀系统，微服务版技术栈（Spring Cloud Alibaba），按「先单体、后微服务」演进路线施工。

## 当前阶段

M0：环境与工程骨架。

## 快速启动

```bash
# 1. 启动中间件（MySQL 8.0.36 + Redis 7.2.4）
docker compose up -d

# 2. 启动应用（JDK 21）
./mvnw spring-boot:run

# 3. 健康检查
curl http://localhost:8080/actuator/health
```

## 技术栈版本

| 组件 | 版本 |
|---|---|
| Spring Boot | 3.2.5 |
| JDK | 21 |
| MyBatis-Plus | 3.5.7 |
| MySQL / Redis | 8.0.36 / 7.2.4（docker） |

> 完整施工计划见会话规划文档；M1 起 Flyway 迁移脚本落 `src/main/resources/db/migration/`。
