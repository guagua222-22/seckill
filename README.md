# 秒杀系统（seckill）

生产级秒杀系统，微服务架构（Spring Cloud Alibaba）。按「先单体、后微服务」路线演进：M1-M4 以单体跑通核心链路，M5 完成微服务拆分，M6 补齐高并发下的稳定性防线。

## 当前阶段

M6：稳定性防线（已完成）——Sentinel 限流/熔断/热点参数 + Caffeine 多级缓存热点隔离 + Redisson 分布式布隆 + 多实例压测验收

## 架构总览

```
                     ┌────────────────────┐
   浏览器 :8080 ────► │      Gateway       │ 入口总闸：GatewayFlowRule 按路由限 QPS，超限直接 429
   （验证台静态页）   │     (webflux)      │
                     └─────────┬──────────┘
          /api/user/**  ┌──────┴──────┐  /api/seckill/**
       /api/goods/**    │             │  /api/order/**
              ┌─────────▼──┐   ┌──────▼────────┐
              │   goods    │   │    seckill    │  实例自保：Sentinel 流控 + 热点参数 + 熔断
              │ 8082 ×N    │◄──┤  8083 ×N      │  热点隔离：Caffeine 本地缓存挡在 Redis 前
              └─────┬──────┘Feign└──────┬──────┘  （压测实例：goods 8092 / seckill 8093）
              ┌─────▼──────┐   lb://    │
              │ user  8081 │◄───────────┘
              └─────┬──────┘
              ┌─────▼────────────────────────────────────────────┐
              │ Nacos :8848   服务注册/发现（LoadBalancer 轮询）    │
              ├──────────────────────────────────────────────────┤
              │ Redis :6379   Lua 预扣/活动缓存/预热契约/分布式布隆  │
              │ RocketMQ :9876（只有 seckill-service 用）          │
              │ MySQL :3307   seckill_user / _goods / _seckill    │
              └──────────────────────────────────────────────────┘
```

两层限流职责不同：**网关**保护的是整个入口带宽（流量大到网关/下游连接池先撑不住时，服务内规则还来不及生效），**服务内 FlowRule**保护的是单个实例的 DB/MQ。

| 模块 | 端口 | 数据库 | 职责 |
|---|---|---|---|
| seckill-common | - | - | 统一返回/错误码/异常/Jackson/MyBatis 配置/Feign 契约 DTO/Redis key 契约 |
| seckill-gateway | 8080 | - | 统一入口：路由分发 + 前端静态页转发 + 入口 QPS 限流（webflux，禁引 common） |
| seckill-user | 8081 | seckill_user | 注册/登录（BCrypt）+ 内部用户存在性接口 |
| seckill-goods | 8082 | seckill_goods | 商品/活动 CRUD、缓存三件套 + 分布式布隆 + 热点本地缓存、预热/对账、内部库存扣减（幂等流水） |
| seckill-seckill | 8083 | seckill_seckill | Lua 预扣、MQ 异步落单、本地消息表、订单域、前端验证台、Sentinel 规则 + 活动本地缓存 |

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

# 4.（可选）Sentinel 控制台：看实时 QPS / 被拦数量 / 熔断状态，不起也不影响限流
bash scripts/sentinel-dashboard.sh --bg     # http://localhost:8858 ，账号密码都是 sentinel
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

穿透（Redisson 分布式布隆 + 空值缓存）/ 击穿（逻辑过期 + SETNX 互斥重建）/ 雪崩（TTL 30min ± 随机）。
> M6 把布隆从 Guava 本地版换成 Redisson bitmap（`goods:bloom:ids`）。本地布隆在多实例下是个反向事故：
> A 实例创建的新商品只进了 A 的过滤器，请求被负载均衡打到 B 时会被判成"一定不存在"直接 404——
> 布隆"绝不误漏"的特性被用反了方向。`tryInit` 幂等，只有首个启动的实例做全量灌入。

## 稳定性防线（M6）

### 限流/熔断规则代码化

规则写在 `SentinelRuleConfig`（服务内）和 `SentinelGatewayConfig`（网关），Sentinel 控制台只读看实时 QPS/熔断状态。
理由：规则是和容量一起演进的「工程常量」——压测得出阈值后改代码走评审；放控制台容易被随手改坏，且无法单测。

| 规则 | 资源 | 阈值（配置可覆盖） | 挡什么 |
|---|---|---|---|
| FlowRule（QPS） | `seckill:createOrder` | 2000 | 下单入口总闸，保护 DB/MQ 不被瞬时流量打穿 |
| ParamFlowRule | `seckill:createOrder` paramIdx=0 | 单活动 1000 | 热点活动不吃光其他活动的配额 |
| DegradeRule（异常比例） | `dep:goods:*` / `dep:user:*` | 50%，熔断 10s | 慢依赖拖死调用方线程（雪崩防线） |
| GatewayFlowRule | 路由 `seckill-service` | 5000 | 入口带宽总闸 |

埋点统一走 `SentinelGuard.call(资源名, 被限流时抛的错误码, 业务动作, 热点参数...)`：被限流快速失败成业务码，
**业务异常原样透传且不计入熔断统计**——否则「库存不足」「已抢过」这类正常业务拒绝会把依赖误判成故障，
把熔断器打开。这条语义有专门单测守着。

### Sentinel 控制台（只读观测）

控制台不是必需的中间件，**没起也不影响任何限流/熔断行为**（规则在代码里，不靠控制台下发）。
要看实时 QPS、被拦数量、熔断状态时再起：

```bash
bash scripts/sentinel-dashboard.sh          # 前台，Ctrl+C 停
bash scripts/sentinel-dashboard.sh --bg     # 后台，日志 /tmp/sentinel-dashboard.log
```

打开 http://localhost:8858 ，账号密码都是 `sentinel`。

- **为什么不做成容器**：被监控的 4 个应用跑在宿主机，Sentinel 客户端会在宿主机开 8719+ 的
  CommandCenter 端口，控制台需要反向连回来。装进容器就多一层 Docker Desktop 网络不确定性，收益为零。
- **为什么 jar 不进仓库**：22MB 且不在 Maven Central（阿里云镜像 404），只能从 GitHub Releases 取。
  按本机工具惯例放 `C:\Soft_Common\sentinel-dashboard\`，脚本里钉了 sha256 校验，缺失时打印下载命令。
- **端口自动递增**：CommandCenter 默认 8719，被占就 +1。同时起 4 个应用时网关拿 8719、seckill 拿 8720，
  控制台按 app 名区分，不必手工配 `transport.port`。
- **没流量就看不到资源**：Sentinel 的资源树是懒建的，`eager: true` 只保证启动即上报心跳
  （应用出现在左侧列表），资源要等第一个请求进来才有。压测时开着看最直观。

> **踩过的坑（时序 bug，值得单独记）**：`SentinelRuleConfig` 最初用 `@PostConstruct` 加载规则，
> 结果控制台里永远只有网关、没有 seckill-service。根因是初始化顺序：yml 里的
> `spring.cloud.sentinel.transport.dashboard` 要靠 SCA 自动配置的 `@PostConstruct` 搬进
> `csp.sentinel.dashboard.server` 系统属性，而**自动配置 Bean 排在用户 Bean 之后实例化**——
> 我的 `@PostConstruct` 抢先把 Sentinel 核心类初始化了，`SimpleHttpHeartbeatSender` 构造时读到的是
> **空的控制台地址列表，而这个列表终身不再重读**，于是该 JVM 一个心跳都不发。客户端日志里只有一行
> `WARNING [SimpleHttpHeartbeatSender] Dashboard server address not configured or not available`，
> 而 CommandCenter 照常监听、手动调 `/registry/machine` 注册后指标也全对——所以极易误判成控制台的问题。
> 同一根因还有个副作用：app 名退回成主类名，metrics 日志被写成
> `com-seckill-seckill-SeckillServiceApplication-metrics.log` 而不是 `seckill-service-metrics.log`。
>
> 修法是改用 `SmartInitializingSingleton.afterSingletonsInstantiated()`：它在**所有单例的
> `@PostConstruct` 之后**、**Web 容器开始收流量之前**触发，两个条件正好都满足。网关侧同样改掉——
> 它当时只是侥幸没踩中（`GatewayRuleManager` 没把 Sentinel 核心类拖起来），但隐患一模一样。
> 排查入口：`C:\Users\<you>\logs\csp\sentinel-record.log.<日期>.*`，
> 对比正常与异常实例的 `App name resolved from ...` 一行即可定位。

### 热点隔离：多级缓存

`is_hot=1` 的商品（goods 侧）和正在被下单的活动（seckill 侧）在 JVM 内多缓存一层 Caffeine（TTL 秒级），
开抢瞬间不再让所有实例齐打 Redis 的同一个热点 key；`Caffeine.get(key, loader)` 自带 per-key single-flight，
同实例内的并发回源被合并成一次。

**分界线（整个设计里最重要的一条）**：本地缓存只放**配置/展示数据**（商品名、价格、活动时间窗），
**库存永远现查现扣**——goods 的 `getDetail` 每次都查 `t_stock`，seckill 每一单都跑 Lua。
展示数据可以短暂不一致，交易数据必须强一致；一旦把含库存的 VO 塞进本地缓存，TTL 窗口内就会卖超。
两侧都有单测锁住这个不变量。

### 多实例压测实测

单机起 6 个实例（gateway 8080 / user 8081 / goods 8082+8092 / seckill 8083+8093），
压测脚本 `scripts/loadtest/load.mjs`（Node 18+，零依赖，按「HTTP 状态/业务码」分桶统计，
所以网关的 HTTP 429 和服务内的 `200/429` 能分开看）。

**① 限流实测**——下单接口 20000 req / 并发 600，直连单个 seckill 实例：

| 结果 | 数量 | 占比 | 实际速率 |
|---|---|---|---|
| `200/429` 被 Sentinel 拦（RATE_LIMITED） | 10324 | 51.6% | ~1032 req/s |
| `200/3002` 放行到业务（已抢过） | 9676 | 48.4% | **969 req/s** |

放行速率 969 req/s 精准贴着 `hot-activity-qps=1000`——绑住的是**热点参数规则**（单活动 1000）而不是
总闸（`order-qps=2000`），因为全部流量都打在同一个 activityId 上。这正是热点参数限流的设计意图：
单个爆款活动不能吃光整个下单入口的配额。

被拦的 10324 个请求**一个都没碰下游**：压测后 Redis 库存仍是 995（1000−5）、`t_order`/`t_seckill_record`/
`t_stock_operation` 都还是 5 条。快速失败不排队，语义与单测 `blockedSkipsAction` 一致。

**② 热点隔离 A/B**——商品详情 10000 req / 并发 100，同一接口只切 `is_hot`：

| 配置 | 吞吐 | p50 | p99 |
|---|---|---|---|
| `is_hot=1`（走 Caffeine） | 2617~2980 req/s | 29~34ms | 112~120ms |
| `is_hot=0`（每次打 Redis） | 1583~1819 req/s | 51~58ms | 142~170ms |

**收益：吞吐 +55%~+88%，p50 下降 33%~50%。**

**③ 横向扩容的三个反直觉观察**（同一份 20000 req / 并发 600 的下单压力，直连 vs 经网关）：

| 入口 | 总吞吐 | 被限流 | 单实例真实业务速率 |
|---|---|---|---|
| 直连 1 个实例 | 2001 req/s | 10324（51.6%） | 969 req/s |
| 经网关 → 2 个实例 | 1286 req/s | **0** | 643 req/s ×2 |

- **Sentinel 的 FlowRule/ParamFlowRule 是单机阈值，不是集群阈值**。经网关时总流量被 Nacos 轮询摊到两个实例，
  每实例只有 ~643 req/s，够不到 1000 的线，于是一个都没拦。集群实际放行量 = 单机阈值 × 实例数，
  **扩容会同步放大放行量**——要按集群总量限流得上 Sentinel 集群流控（token server），或在网关按路由限总闸。
- **直连那 2001 req/s 是虚高的**：其中一半是被 Sentinel 秒拒的空转请求，真实业务吞吐只有 969 req/s。
  两实例集群做到 1286 req/s，只有 **+33% 而不是 2×**——因为单活动的库存 key 在 Redis 里是天然串行点，
  加实例扩不动它。这也是「热点隔离 / 库存分片」存在的根本理由。
- **网关自身开销约 18%**：库存查询接口直连单实例 4125 req/s，经网关 3397 req/s（并发 600）。

**④ 其他验收**：

- **分布式布隆跨实例共享**：在 8092 上创建的商品，从 8082 和网关都能读到（6/6 成功）。Guava 本地布隆下这几乎必然 404。
- **Nacos 负载均衡**：经网关压 6000 单，两实例各 +3001；压 20000 单，各 +10001/+10003——稳定 50/50 轮询
  （用 `/actuator/metrics/http.server.requests` 的 COUNT 前后差值核对）。
- **网关层 HTTP 429 未实测到**：`GatewayFlowRule` 阈值 5000，而本机 HTTP 天花板约 4100 req/s
  （单进程 3397、双压测进程合计 4076 已饱和），物理上够不到。要演示需临时把
  `gateway.sentinel.seckill-route-qps` 调低重启网关。这条规则在本机属于「备而不用」。

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
node scripts/loadtest/load.mjs --mode detail --base http://localhost:8080 --goods <id> --total 10000 --concurrency 100
```

- 单测：user 6 + goods 34 + seckill 49 = 89（Lua 并发脚本测试直连 Redis db15，需 redis 容器在跑）
- e2e：JDK HttpClient 直连网关，走"网关→Nacos→服务→MQ→三库"真实路径；环境未起自动跳过
- 覆盖率：三业务服务 service 层 LINE ≥ 60%
- Sentinel 规则是 **JVM 全局静态状态**，每个用到规则的测试类必须在 `@AfterEach` 里 `loadRules(List.of())` 清空，否则污染同批次其他测试

## 技术栈版本

| 组件 | 版本 |
|---|---|
| Spring Boot / JDK | 3.2.5 / 21 |
| Spring Cloud / Alibaba | 2023.0.2 / 2023.0.1.0（Nacos 2.3.2） |
| MyBatis-Plus / Flyway | 3.5.7 / 9.22.3 |
| MySQL / Redis | 8.0.36 / 7.2.4（docker） |
| RocketMQ | 4.9.4（broker）+ starter 2.3.2（client 钉 5.3.1） |
| Redisson | 3.32.0 |
| Sentinel / Caffeine | 1.8.6 / 3.1.8（均由 SCA / Boot BOM 托管，pom 不写版本） |

### 环境踩坑记录

- **RocketMQ 端口**：10909/10911 落在 Windows WinNAT 保留段 10885-10984，broker 监听端口改 10996（客户端经 namesrv 自动获取）
- **RocketMQ client 版本**：SCA BOM 会把 rocketmq-client 压到 5.1.4（与 starter 2.3.2 不兼容），根 pom 显式钉 5.3.1
- **Feign + loadbalancer**：SC 2023 必须显式引入 loadbalancer，否则 `lb://` 报 No LoadBalancerClient
- **gateway 依赖纪律**：严禁引 starter-web/seckill-common（webflux 冲突启动即挂）
- **Nacos**：9848 gRPC 端口必须映射；WSL2 内存预算内堆压到 256m
- **跨服务时间/Long 对称**：三服务 `spring.jackson.*` 逐字一致 + common 共享 JacksonConfig
- **Jackson `readTree` 毁 BigDecimal scale**（M6 压测时抓到）：`readTree` + `treeToValue` 会先把 JSON 浮点解析成 `DoubleNode`，金额经 double 一转就丢精度——Redis 里存 `6999.00`，读出来变 `6999.0`。缓存反序列化必须直接 `readValue(json, Class)`，金额绝不允许走 double
- **Gateway 限流响应**：webflux 下必须注册 `SentinelGatewayBlockExceptionHandler`，否则 `BlockException` 落成 500 而不是 429；`SentinelGatewayFilter` 要 `@Order(HIGHEST_PRECEDENCE)`，被拒的请求不该再消耗路由资源
- **Redisson `tryInit` 参数只写一次**：`goods:bloom:ids` 已存在时改 `expected-insertions`/`false-probability` 不生效，必须先 `DEL` 再重启
- **Windows GBK 控制台毁中文请求体**：`curl -d '{"name":"中文"}'` 会被编码搞坏，服务端报 400「请求体格式错误」。JSON 用 UTF-8 写进文件再 `--data-binary @file`，并显式带 `charset=UTF-8`
- **运行中的 JVM 锁 jar**：Windows 下服务在跑时 `mvn package` 覆盖 `target/*.jar` 会失败。但 IDEA 里的服务是从 `target/classes` 起的，改完代码重启即生效，不必打包
- **Sentinel 规则加载禁用 `@PostConstruct`**：会抢在 SCA 自动配置写入 `csp.sentinel.dashboard.server` 之前初始化 Sentinel 核心，心跳发送器读到空地址列表且终身不重读 → 控制台永远看不到该服务。必须用 `SmartInitializingSingleton.afterSingletonsInstantiated()`，详见「稳定性防线（M6）· Sentinel 控制台」
- **Sentinel 控制台 jar 不在 Maven Central**：阿里云镜像 404，只能从 GitHub Releases 取（22MB）。放 `C:\Soft_Common\sentinel-dashboard\`，由 `scripts/sentinel-dashboard.sh` 校验 sha256 后启动；jar 不进仓库
