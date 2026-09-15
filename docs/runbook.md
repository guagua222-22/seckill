# 本机运行手册

适用 Windows + Docker Desktop Linux engine + JDK 21。这里启动的是本机开发环境，服务仍在宿主机 JVM 中运行；不是 Kubernetes 或生产部署脚本。

## 1. 第一次启动

确认 `java --version` 显示 21，`docker info` 能连接服务端，`docker compose version` 可用。首次联网下载依赖与镜像较慢；本地还需给 Docker 和四个 JVM 留足内存。Compose 需支持 `up --wait`。

在仓库根目录执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/dev/start.ps1
```

启动顺序：

1. 验证 Java、Docker 和端口归属；遇到其他项目占用端口直接报错。
2. 启动 `docker-compose.yml` 的 MySQL、Redis、RocketMQ、Nacos，等待 Compose 健康检查。
3. 执行 `scripts/db/init-databases.sql`，只做三次 `CREATE DATABASE IF NOT EXISTS`，保留原有数据。每个服务随后由 Flyway 管自己的表。
4. Maven 全模块 `package -DskipTests`。首次不要只构建某个下游模块，否则公共模块依赖可能未准备好。
5. 顺序启动 user、goods、seckill、gateway；启用 `monitoring` profile，等待管理端口 health 的 `status=UP`。
6. 调用 M7 监控启动器，启动 Prometheus 和 Grafana。容器启动后可用监控验收脚本检查抓取和面板。

不自动灌测试用户、不创建活动、不执行压测。MQ 的 Compose 健康检查主要验证进程存活，完整链路仍需业务验收；`UP` 不能代替一次真实交易。

总启动器对新 user JVM 显式设置 `--seckill.seed.user-count=0`，避免终端遗留 `SEED_USER_COUNT` 导致日常启动重新造数。需要准备压测用户时，另行执行专门的数据准备流程。

## 2. 常用命令与参数

```powershell
# 已构建可执行 jar 时跳过构建
powershell -File scripts/dev/start.ps1 -SkipBuild
# 将已有活动加入库存监控白名单；示例 ID 必须换成自己的
powershell -File scripts/dev/start.ps1 -SkipBuild -ActivityIds "2099130660617097217"
# 只启动中间件和业务，新 JVM 不启用 monitoring profile
powershell -File scripts/dev/start.ps1 -WithoutMonitoring
# 较慢机器可延长每个服务的等待上限，允许 10–600 秒
powershell -File scripts/dev/start.ps1 -TimeoutSeconds 300
# 查端口、健康、是否属于启动器管理
powershell -File scripts/dev/status.ps1
# 结束本脚本创建的 JVM，保留 Docker、数据卷、IDEA 进程
powershell -File scripts/dev/stop.ps1
```

`ActivityIds` 接受最多 32 个逗号分隔的正整数。不指定时，新 JVM 不监控活动库存，Grafana 库存面板没有曲线是预期行为。不存在的库存 key 可能显示 -1，含义见 [M7](M7-monitoring.md)。

`WithoutMonitoring` 只影响此次新启动的 JVM，且跳过监控启动步骤；它不会关闭已经运行的监控容器。

**复用规则：** 已运行且属于本仓库的 Java 服务只做健康检查。脚本不会替你更新其代码、profile 或活动 ID；即使 Maven 又构建成功，旧进程仍执行旧代码。IDEA 服务请在 IDEA 修改启动配置并重启。脚本管理的服务可以在无在途请求时先 `stop.ps1`，再 `start.ps1`。

**停止规则：** `logs/dev/processes.json` 保存 PID、启动随机标记与 jar 路径。停止时同时核对 Java 进程名、命令行标记和工作区内 jar 路径，避免 PID 被复用后误杀其他进程。Windows `Stop-Process` 是强制终止，不能用来演示交易优雅停机；本地压测结束后再用。启动/停止共用文件锁，避免两个终端交错管理进程。

新服务使用 `logs/dev/<服务>-<随机标记>/` 下的 jar 副本，标准输出和错误分别保存为 `stdout.log`、`stderr.log`。这样 Maven 不会覆盖 Windows 正在锁定的运行 jar。启动失败只回收本轮新建且身份匹配的 JVM；已运行服务与容器保留，日志也保留。旧运行目录不会自动清理。

## 3. 地址和 Docker 分组

| 组件 | 本机地址 / 端口 | 用途 |
|---|---|---|
| Gateway | [8080](http://localhost:8080/) | 验证台与统一 API |
| user / goods / seckill | 8081 / 8082 / 8083 | 开发时可直连 |
| 管理端口 | 19080 / 19081 / 19082 / 19083 | `/actuator/health`、`/actuator/prometheus` |
| MySQL | 3307 | 三个业务库；开发配置 root / root123 |
| Redis | 6379 | 应用默认 DB0；测试专用 DB15 |
| Nacos | [8848/nacos](http://localhost:8848/nacos) + 9848 | HTTP 控制台与客户端 gRPC |
| RocketMQ | 9876 / 10996 | NameServer / broker remoting |
| Prometheus | [9090/targets](http://localhost:9090/targets) | 抓取状态 |
| Grafana | [3000/d/seckill-m7](http://localhost:3000/d/seckill-m7) | 自动导入看板；admin / 本地 `.env.monitoring` 密码 |

保留既有两个 Compose 组：`seckill` 管中间件，`seckill-monitoring` 管监控。两组生命周期可以独立管理，启动器不会创建第三组。基础组使用明确的项目名 `seckill`，避免克隆目录更名或环境变量影响分组。

总启动器使用 `--no-recreate` 保留已有容器；修改 Compose 后重复启动不会自动应用新配置。确需升级容器配置时，先安排维护，再单独执行相应 Compose 更新命令。

`.env.monitoring`、`logs/` 已被 Git 忽略。Grafana 密码仅在数据卷第一次初始化时生效，修改环境文件不等于重置已有账号密码。开发配置的默认密码和关闭鉴权只适用于受控本机环境；不要把这些端口直接作为公网部署配置。

## 4. IDEA 和手动启动

IDEA 选择 JDK 21，先导入根 `pom.xml`，为四个 main 类分别建立配置；需要监控时将 Active profiles 设为 `monitoring`。秒杀服务可加程序参数 `--seckill.monitoring.activity-ids=你的活动ID`。类名见 [公共脚本](../scripts/dev/common.ps1)。

手动运行单模块 Maven 前，先在根目录执行：

```powershell
.\mvnw.cmd -B -ntp '-DskipTests' install
# 在独立终端分别运行 user / goods / seckill / gateway；以下是 user 的例子
.\mvnw.cmd -pl seckill-user spring-boot:run '-Dspring-boot.run.profiles=monitoring'
```

前提仍是中间件启动且三库存在。不要执行 `scripts/db/` 中的历史迁移或删除旧库脚本来替代幂等建库。

Sentinel Dashboard 是可选的规则实验工具，M7 Prometheus/Grafana 不依赖它；现有 [启动脚本](../scripts/sentinel-dashboard.sh) 需要 Bash，具体下载位置与参数以脚本为准。

## 5. 排错顺序

| 现象 | 先检查什么 |
|---|---|
| 启动器提示端口冲突 | `status.ps1` 和 IDEA 配置；不要批量结束所有 Java 进程 |
| 业务端口 `/actuator/health` 返回 404 / code=500 | monitoring 已把 health 移到管理端口；用 19080–19083 检查 |
| 服务已启动但 Gateway 暂时 503 | Nacos 注册和实例列表是否收敛；9848 是否可达；看服务日志 |
| MQ 连接失败 | broker 日志、`rocketmq/broker.conf` 的 10996、宿主机到 broker 的地址；端口被 Windows 保留时先核对，不盲改全部映射 |
| Grafana 有历史掉线告警，当前 JVM 有曲线 | 对照相同时间范围的 Targets 和告警恢复时间，历史触发不代表现在仍故障 |
| 库存 / 缓存命中率无数据 | 是否配置有效活动、是否有请求；零分母没有命中率，不能硬填 100% |
| 中文脚本报奇怪的引号/语法错误 | Windows PowerShell 5.1 要以 UTF-8 BOM 保存中文 `.ps1` |
| Maven 无法覆盖 jar | 是否直接运行 `target` 中的 jar；先从原启动入口停服，之后改用脚本副本 |

```powershell
docker compose -p seckill -f docker-compose.yml ps
docker compose -p seckill -f docker-compose.yml logs --tail 80 rocketmq-broker
Invoke-RestMethod http://127.0.0.1:19083/actuator/health
powershell -File scripts/monitoring/verify.ps1
```

## 6. 验证与恢复边界

执行 `mvnw.cmd -B -ntp verify` 会访问本机 Redis 的测试专用 DB15 并清空该库，请确保 DB15 没有其他用途。默认测试不跑历史 e2e；原因见 [待办](known-limitations.md)。启动器自身的隔离测试使用 `scripts/dev/test.ps1`，不停止真实 Java 服务。

进程清单丢失时，脚本不会按端口“猜测”然后杀进程；回到原启动入口或人工核实进程身份再处理。启动失败留下的中间件无需删除卷重新来过，优先读日志修正配置后再次启动。
