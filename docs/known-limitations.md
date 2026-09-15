# 已知边界与后续验证

本页记录 M8 对当前实现的代码审查结果。以下故障窗口尚未逐项通过真实故障注入复现；它们是需要处理和验证的风险，不能写成“已修复”。M8 完成的是文档与开发交付收口，没有顺带改动这些交易逻辑。

P1 表示上线前优先处理的正确性或故障恢复缺口；P2 表示工程完备性、可观测性与验证缺口。这里的优先级是本项目后续学习安排。

## 1. P1：库存操作捕获唯一键异常后仍可能提交扣减

位置：[StockOperationServiceImpl.deduct](../seckill-goods/src/main/java/com/seckill/goods/service/impl/StockOperationServiceImpl.java)。

当前顺序是查 requestId → 条件扣库存 → 插唯一流水。两个事务同时查到无记录后，可能先后扣减库存；后者插入碰唯一键，代码只 catch 并记录日志，没有向事务代理抛出异常或显式标记回滚。不能按原注释推定此前 UPDATE 一定回滚。订单不重复，并不代表库存没有多扣。

建议先用真实 MySQL 集成测试，让两个同 requestId 的事务都越过前置查询，最终必须只减 1、只留 1 条有效操作记录；验证事务边界后再选择先占幂等流水或独立事务回滚等实现。Mockito 抛异常只能验证分支，不能证明数据库事务真的回滚。

同一方法只按 requestId 是否存在判断成功，没有区分已回滚状态；请求数量字段可变化而实际固定扣 1。后续需明确“回滚后是否允许原 requestId 重用”、校验请求载荷一致性和数量契约。

## 2. P1：Redis 回滚由两个独立命令组成

位置：[RedisStockRollback.rollback](../seckill-seckill/src/main/java/com/seckill/seckill/service/impl/RedisStockRollback.java)。

当前先 SREM 资格，再 INCR 库存。若在两步之间中断，重试时 SREM 返回 0，库存不会补回。顺序执行可防部分重复回滚，但不构成原子补偿。

建议将同一活动的资格撤销和库存恢复放入一个 Lua 脚本，并定义过期 key、活动重建与旧消息回滚的语义。验收应覆盖重复回滚、并发回滚、中断恢复，最终资格与库存同时一致；不能仅断言调用了 INCR。

## 3. P1：Lua 到本地消息表仍有无持久记录窗口

位置：[SeckillOrderServiceImpl.doSeckill](../seckill-seckill/src/main/java/com/seckill/seckill/service/impl/SeckillOrderServiceImpl.java)、[RecordMessageWriter](../seckill-seckill/src/main/java/com/seckill/seckill/service/impl/RecordMessageWriter.java)。

Lua 成功后才写本地流水与消息。若 JVM 此时退出，Redis 已预扣但 DB 没有投递意图，扫描消息表无法恢复这条请求。部分异常类型有同步回滚，不能覆盖进程崩溃或所有运行时异常。DB 事务只保证“流水和消息一起提交”，没有把 Redis 纳入其中。

建议先决定持久准入意图、库存预占状态和恢复协议，再做故障测试：在 Lua 返回后、DB 提交前退出进程，重启后每个预占都应能关联终态订单或可验证的释放记录。不能只看定时任务有没有执行。

## 4. P1：跨库补偿失败可能被终态掩盖

位置：[DbOrderWriter](../seckill-seckill/src/main/java/com/seckill/seckill/service/impl/DbOrderWriter.java)、[SeckillOrderConsumerService](../seckill-seckill/src/main/java/com/seckill/seckill/service/impl/SeckillOrderConsumerService.java)。

远端 goods 扣库存已提交后，本地订单唯一键冲突会调用回滚；回滚再失败时只记日志，随后仍抛 ALREADY_ORDERED。消费端把该业务码视为幂等成功、推进流水到 1 并返回。不能直接宣称它必然继续重试补偿，按排队状态扫描的恢复逻辑也可能看不到该缺口。

建议明确订单结果与补偿结果的独立状态，持久化未完成的补偿。验收注入“远端提交 → 本地插单冲突 → 回滚超时”，恢复后核对订单、每个 requestId 的库存操作与流水终态，不能只看消息 ACK。

## 5. P1：Redis 全故障降级的捕获范围不完整

位置：[SeckillOrderServiceImpl.createOrder/loadActivityFromSource](../seckill-seckill/src/main/java/com/seckill/seckill/service/impl/SeckillOrderServiceImpl.java)。

读取活动 Redis 缓存发生在降级 try/catch 外，全故障时可能先失败而根本到不了 DB 直写；catch 又使用较宽的 DataAccessException，DB 异常也可能被标成 Redis 故障。不能写“Redis 挂了自动无损切换”。

建议拆分依赖故障类别，明确冷缓存与热缓存两种降级行为；在隔离环境验证 Redis 不可达、超时、恢复，以及降级和正常路径并发竞争时的库存一致性和数据库保护。

## 6. P2：布隆初始化与缓存冷启动

位置：[GoodsBloomFilter](../seckill-goods/src/main/java/com/seckill/goods/cache/GoodsBloomFilter.java)、[GoodsServiceImpl](../seckill-goods/src/main/java/com/seckill/goods/service/impl/GoodsServiceImpl.java)。

布隆 `tryInit` 成功后逐条装载；中途退出会留下已存在但不完整的过滤器，下一实例可能跳过全量装载。数学上的“无假阴性”不能代替业务数据完整性。建议版本化构建与就绪切换，并验证中断初始化和增量登记失败。

逻辑过期锁主要作用于已存在的过期缓存；物理 key 丢失时直接回 DB，不能声称所有冷缓存都受互斥保护。需要独立验证热点 key 物理过期或 Redis 清空后的回源压力。

## 7. P2：历史 e2e 目前不能充当全链路验收

位置：[SeckillFullStackE2ETest](../seckill-seckill/src/test/java/com/seckill/seckill/SeckillFullStackE2ETest.java)。

- 使用固定活动日期 2026-09-11 至 2026-09-12，已过期。
- 探测业务端口 8080 的 actuator health；monitoring 把 health 移到管理端口后可能因前置假设而跳过。
- 多线程写普通 ArrayList，requestId 收集不安全。
- 清理依赖 activityId 已生成，中间准备失败可能留下测试数据；Redis/布隆残留也需处理。

后续改为动态时间窗、明确“环境不可用失败还是跳过”的策略、并发安全集合和按本轮数据标识清理。把此测试作为独立集成任务运行，并检查实际执行数量；默认 Maven 100 个测试通过不代表它执行了。

## 8. 当前明确未完成的生产能力

| 能力 | 当前边界与下一步 |
|---|---|
| 身份认证与授权 | BCrypt 注册登录不等于会话/JWT 与资源授权；内部路由未暴露到网关不等于服务端口安全 |
| 库存/订单全生命周期 | 支付、超时取消、退款、活动调整与恢复协议尚不完整 |
| Redis Cluster / HA | 当前单机 Redis；Lua 多 key 无统一 hash tag，迁移 Cluster 前须重新设计 slot 和故障语义 |
| 分布式锁边界 | 消费端固定租期 10 秒；超时执行、锁过期与 unlock 异常要专项验证，不能只说 Redisson 自动续期 |
| 消息运维 | 本地重发最多 10 次；缺重试耗尽告警、积压/消费延迟指标与人工恢复工具 |
| 监控通知 | 5 条规则可在 Prometheus 看触发状态；未配置 Alertmanager 或外部通知，未建完整链路追踪 |
| 容灾与部署 | 同机三库与多实例不提供物理高可用；默认凭据、关闭鉴权及端口映射需重新制定上线配置 |

建议下一步按 **数据库扣减事务 → Redis 原子回滚 → 跨库补偿状态 → 故障注入验收** 的顺序推进，再扩展吞吐和部署。面试时把这些列为你能解释的工程边界，不要声称已达到任意故障下零丢单、零库存偏差。
