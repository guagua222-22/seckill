-- seckill-service 建表（M5 拆分：从单体 V1 中切出 order/seckill 域三张表，DDL 原样）
-- 库：seckill_seckill，本服务独占（order 域已并入），其他服务禁止直连本库表

-- 订单表（order 域，M5 并入 seckill-service）
CREATE TABLE t_order (
  id          BIGINT UNSIGNED NOT NULL COMMENT '订单ID',
  order_no    VARCHAR(64) NOT NULL COMMENT '订单号(雪花)',
  user_id     BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
  activity_id BIGINT UNSIGNED NOT NULL COMMENT '活动ID',
  goods_id    BIGINT UNSIGNED NOT NULL COMMENT '商品ID',
  goods_name  VARCHAR(128) NOT NULL COMMENT '商品名快照',
  price       DECIMAL(10,2) NOT NULL COMMENT '成交价快照',
  status      TINYINT NOT NULL DEFAULT 0 COMMENT '0待支付 1已支付 2已取消',
  request_id  VARCHAR(64) NOT NULL COMMENT '幂等键(客户端请求ID)',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  pay_time    DATETIME DEFAULT NULL COMMENT '支付时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_no (order_no),
  UNIQUE KEY uk_user_activity (user_id, activity_id) COMMENT '一人一单业务幂等',
  UNIQUE KEY uk_request_id (request_id) COMMENT '请求级幂等',
  KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

-- 秒杀预扣流水表（seckill 域，对账用）
CREATE TABLE t_seckill_record (
  id          BIGINT UNSIGNED NOT NULL COMMENT '流水ID',
  request_id  VARCHAR(64) NOT NULL COMMENT '与本地消息、订单幂等键一致',
  user_id     BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
  activity_id BIGINT UNSIGNED NOT NULL COMMENT '活动ID',
  status      TINYINT NOT NULL DEFAULT 0 COMMENT '0已预扣 1已下单 2已回滚',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_request (request_id),
  KEY idx_activity_status (activity_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='秒杀预扣流水表';

-- 本地消息表（seckill 域，可靠消息）
CREATE TABLE t_local_message (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增ID',
  message_id      VARCHAR(64) NOT NULL COMMENT '消息唯一ID=request_id',
  topic           VARCHAR(64) NOT NULL COMMENT 'MQ主题',
  tag             VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'MQ标签',
  body            TEXT NOT NULL COMMENT 'JSON消息体',
  status          TINYINT NOT NULL DEFAULT 0 COMMENT '0待发送 1已发送 3失败终态',
  retry_count     INT NOT NULL DEFAULT 0 COMMENT '已重试次数',
  next_retry_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间',
  create_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_message_id (message_id),
  KEY idx_status_retry (status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表';
