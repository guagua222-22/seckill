# M8 交付与验收记录

验收日期：2026-09-15；环境：Windows、本机 JDK 21、Docker Desktop、既有四个 Java 服务与两个 Compose 项目。

## 已交付

- README 重整：架构图、模块/端口、启动入口、里程碑及准确的性能摘要。
- [架构与事务边界](architecture.md)：正常时序、状态图、幂等/缓存/限流职责。
- [运行手册](runbook.md)：一键启动、复用、归属校验停止、日志及故障处理。
- [性能与证据](performance.md)、[结果模板](performance-run-template.md)：历史来源、统计口径与复测方法。
- [面试与简历](interview.md)：简历定稿、30 秒/两分钟介绍、核心追问与学习顺序。
- [已知边界](known-limitations.md)：源码审查发现的事务和恢复缺口，以及下一步测试标准。
- 中文注释的 `scripts/dev/start.ps1`、`stop.ps1`、`status.ps1`、`common.ps1` 和隔离测试；M7 监控启动器增加可选 `NoRecreate` 参数。

## 实际验证结果

| 检查 | 结果 |
|---|---|
| 根目录 `mvnw.cmd -B -ntp verify` | BUILD SUCCESS，100 个默认 Java 测试：user 6 / goods 35 / seckill 58 / gateway 1；失败、错误、跳过均为 0；现有 JaCoCo 门槛通过 |
| Windows PowerShell 5.1 脚本回归 | 35 项断言通过 |
| PowerShell 7 脚本回归 | 同样 35 项断言通过 |
| 实际 `start.ps1 -SkipBuild` | 复用四个已有项目 JVM；user/goods/seckill/gateway 的 PID 均保持原值；管理 health 全部 UP |
| 修正后的容器复用 | 启动前后 `docker ps` 的容器 ID/名字列表一致，沿用两个组 |
| `scripts/monitoring/verify.ps1` | 四个 target UP；Grafana 数据源正常；16 个面板、18 条查询、5 条告警规则检查通过 |
| 文档与差异 | 本地 Markdown 链接检查、PowerShell AST 语法检查、`git diff --check` 通过 |

脚本隔离测试真实使用临时文件与文件锁，以模拟边界替代 Java、Docker、端口和健康接口，执行原始启动/停止控制流程。覆盖：带空格 jar 路径、空/单/多进程清单、互斥、原生命令失败、管理端口就绪、重复启动、PID 身份冲突、启动中途退出清理、无监控模式和禁止隐式造数。测试不会杀真实进程。

首次实际启动验证时，尚未加入 `--no-recreate`，Compose 因配置差异重建了 MySQL 容器；数据卷保留，健康恢复。随后启动器加入保留已有容器的选项，再次验证全部容器 ID 不变。需要应用 Compose 新配置时应单独维护，普通启动不自动升级容器。

本机原始日志保存在被忽略的 `logs/m8-maven-verify.log`、`logs/m8-dev-test-ps51.log`、`logs/m8-dev-test-ps7.log`、`logs/m8-monitoring-verify.log`；仓库内保留本页的检查范围与结果。

## 验证边界

没有为了演示冷启动而停止现有 IDEA 服务；真实环境验证的是复用路径，新 JVM 创建、失败清理和停止路径由隔离测试覆盖。没有在全新空卷机器上执行一次完整安装，没有重跑历史容量压测，也没有运行当前存在缺陷的 e2e 或注入真实交易故障。

M8 没有修改前端或交易 Java 逻辑。里程碑交付完成不表示全部生产一致性问题已解决，下一步优先处理 [已知边界](known-limitations.md) 中的 P1 项。
