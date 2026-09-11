-- M5 微服务拆分：旧 seckill 库 → 三个新库的一次性数据迁移
-- 执行前提：三个新服务的 Flyway 已建好各自表结构；应用全部停止
-- 执行方式：docker exec -i seckill-mysql mysql -uroot -proot123 < scripts/db/migrate-data.sql
--
-- 为什么用 INSERT IGNORE ... SELECT：
-- 1. 新表 DDL 与旧库逐字一致，列顺序相同，SELECT * 安全
-- 2. 雪花 ID 原样保留（订单/流水/消息的幂等键引用关系不断裂）
-- 3. IGNORE 使脚本可重复执行（重复执行只跳过已存在的行）
-- t_stock_operation 是拆分新增表，无历史数据，不迁移
INSERT IGNORE INTO seckill_user.t_user                SELECT * FROM seckill.t_user;
INSERT IGNORE INTO seckill_goods.t_goods              SELECT * FROM seckill.t_goods;
INSERT IGNORE INTO seckill_goods.t_stock              SELECT * FROM seckill.t_stock;
INSERT IGNORE INTO seckill_goods.t_seckill_activity   SELECT * FROM seckill.t_seckill_activity;
INSERT IGNORE INTO seckill_seckill.t_order            SELECT * FROM seckill.t_order;
INSERT IGNORE INTO seckill_seckill.t_seckill_record   SELECT * FROM seckill.t_seckill_record;
INSERT IGNORE INTO seckill_seckill.t_local_message    SELECT * FROM seckill.t_local_message;
