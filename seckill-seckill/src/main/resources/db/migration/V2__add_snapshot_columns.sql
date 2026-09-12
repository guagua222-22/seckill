-- V2：冗余快照字段（用户看数据方便 + 反范式换查询效率）
--
-- 为什么加冗余字段：拆库后 t_order/t_seckill_record 里只有 user_id/activity_id，
-- 要看到"谁抢了什么"必须跨库 join seckill_user/seckill_goods，查看费劲。
-- 下单时把 username/activity_name 快照进来（与 goods_name 快照同一思想）：
-- 查询零 join、历史可读，代价是占用一点存储——秒杀系统标准做法（反范式）。
--
-- 回填说明：历史行用跨库子查询回填（同 MySQL 实例内允许跨库查询）。
-- 用户/活动不存在（如已被删除）的历史行填 ''，不阻塞迁移。

-- 1. 订单表加用户名与活动名快照
ALTER TABLE t_order
  ADD COLUMN username      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '用户名快照(反范式)' AFTER user_id,
  ADD COLUMN activity_name VARCHAR(128) NOT NULL DEFAULT '' COMMENT '活动名快照(反范式)' AFTER activity_id;

-- 2. 流水表加用户名与活动名快照
ALTER TABLE t_seckill_record
  ADD COLUMN username      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '用户名快照(反范式)' AFTER user_id,
  ADD COLUMN activity_name VARCHAR(128) NOT NULL DEFAULT '' COMMENT '活动名快照(反范式)' AFTER activity_id;

-- 3. 历史数据回填（跨库子查询；连接账号 root 有三库权限）
UPDATE t_order o
  SET o.username = COALESCE((SELECT u.username FROM seckill_user.t_user u WHERE u.id = o.user_id), ''),
      o.activity_name = COALESCE((SELECT a.activity_name FROM seckill_goods.t_seckill_activity a WHERE a.id = o.activity_id), '')
  WHERE o.username = '' OR o.activity_name = '';

UPDATE t_seckill_record r
  SET r.username = COALESCE((SELECT u.username FROM seckill_user.t_user u WHERE u.id = r.user_id), ''),
      r.activity_name = COALESCE((SELECT a.activity_name FROM seckill_goods.t_seckill_activity a WHERE a.id = r.activity_id), '')
  WHERE r.username = '' OR r.activity_name = '';
