-- goods-service 建表（M5 拆分：从单体 V1 中切出 goods 域三张表 + 新增扣减流水表）
-- 库：seckill_goods，本服务独占，其他服务禁止直连本库表

CREATE TABLE t_goods (
  id           BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
  goods_name   VARCHAR(128) NOT NULL COMMENT '商品名称',
  description  VARCHAR(512) DEFAULT NULL COMMENT '商品描述',
  normal_price DECIMAL(10,2) NOT NULL COMMENT '日常价',
  status       TINYINT NOT NULL DEFAULT 1 COMMENT '1上架 0下架',
  create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商品表';

-- 库存表（秒杀库存独立于日常库存，与商品详情读写分离避免行锁互相拖累）
CREATE TABLE t_stock (
  id              BIGINT UNSIGNED NOT NULL COMMENT '库存ID',
  goods_id        BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
  total_stock     INT UNSIGNED NOT NULL COMMENT '总库存',
  available_stock INT UNSIGNED NOT NULL COMMENT '可用库存',
  sold_count      INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已售数量',
  version         INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  update_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_goods (goods_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存表';

CREATE TABLE t_seckill_activity (
  id            BIGINT UNSIGNED NOT NULL COMMENT '活动ID',
  activity_name VARCHAR(128) NOT NULL COMMENT '活动名称',
  goods_id      BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
  seckill_price DECIMAL(10,2) NOT NULL COMMENT '秒杀价',
  total_stock   INT UNSIGNED NOT NULL COMMENT '活动库存(预热到Redis)',
  start_time    DATETIME NOT NULL COMMENT '开始时间',
  end_time      DATETIME NOT NULL COMMENT '结束时间',
  status        TINYINT NOT NULL DEFAULT 0 COMMENT '0未开始 1进行中 2已结束',
  is_hot        TINYINT NOT NULL DEFAULT 0 COMMENT '0普通 1热点(本地缓存隔离)',
  create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  KEY idx_goods (goods_id),
  KEY idx_time_status (start_time, end_time, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='秒杀活动表';

-- 库存扣减操作流水（M5 拆分新增）：
-- 拆分前"扣库存+插订单"同库同事务；拆分后扣库存是跨服务 Feign 调用、没有共享事务，
-- Feign 超时重试/MQ 重投会造成重复扣减。本表以 request_id 唯一索引做幂等，
-- 保证同一笔下单请求的库存操作（扣减/回滚）只生效一次
CREATE TABLE t_stock_operation (
  id          BIGINT UNSIGNED NOT NULL COMMENT '操作ID',
  request_id  VARCHAR(64) NOT NULL COMMENT '幂等键(与下单requestId一致)',
  goods_id    BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
  activity_id BIGINT UNSIGNED NOT NULL COMMENT '活动ID',
  amount      INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '扣减数量',
  status      TINYINT NOT NULL DEFAULT 0 COMMENT '0已扣减 1已回滚',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_request (request_id),
  KEY idx_goods (goods_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存扣减操作流水(跨服务补偿幂等依据)';
