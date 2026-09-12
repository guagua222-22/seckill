-- V2：goods 库冗余 name 快照（与 seckill 库 V2 同一思想：有外键 ID 就要有对应名字）
--
-- 为什么：t_seckill_activity / t_stock / t_stock_operation 里只有 goods_id（流水还有 activity_id），
-- 在 Navicat 里看这些表必须回 t_goods / t_seckill_activity 按 ID 对照，非常费劲。
-- 写入时把名字快照进来，表自身即可阅读。
--
-- 语义区分（重要）：
-- - t_stock_operation 是【对账流水】，名字是"操作发生时"的快照，永远不更新（历史可读）；
-- - t_stock / t_seckill_activity 是【配置行】，商品改名时由 GoodsServiceImpl.update 同步刷新，
--   保证看到的永远是当前名字。
--
-- 回填：同库 join 直接回填；商品/活动已不存在的行填 ''，不阻塞迁移。

-- 1. 活动表加商品名快照
ALTER TABLE t_seckill_activity
  ADD COLUMN goods_name VARCHAR(128) NOT NULL DEFAULT '' COMMENT '商品名快照(反范式)' AFTER goods_id;

-- 2. 库存表加商品名快照
ALTER TABLE t_stock
  ADD COLUMN goods_name VARCHAR(128) NOT NULL DEFAULT '' COMMENT '商品名快照(反范式)' AFTER goods_id;

-- 3. 库存操作流水加商品名与活动名快照
ALTER TABLE t_stock_operation
  ADD COLUMN goods_name    VARCHAR(128) NOT NULL DEFAULT '' COMMENT '商品名快照(反范式,操作时点)' AFTER goods_id,
  ADD COLUMN activity_name VARCHAR(128) NOT NULL DEFAULT '' COMMENT '活动名快照(反范式,操作时点)' AFTER activity_id;

-- 4. 历史数据回填（同库 join）
UPDATE t_seckill_activity a
  JOIN t_goods g ON g.id = a.goods_id
  SET a.goods_name = g.goods_name
  WHERE a.goods_name = '';

UPDATE t_stock s
  JOIN t_goods g ON g.id = s.goods_id
  SET s.goods_name = g.goods_name
  WHERE s.goods_name = '';

UPDATE t_stock_operation o
  LEFT JOIN t_goods g ON g.id = o.goods_id
  LEFT JOIN t_seckill_activity a ON a.id = o.activity_id
  SET o.goods_name = COALESCE(g.goods_name, ''),
      o.activity_name = COALESCE(a.activity_name, '')
  WHERE o.goods_name = '' OR o.activity_name = '';
