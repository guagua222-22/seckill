-- user-service 建表（M5 拆分：从单体 V1 中切出 t_user，DDL 原样）
-- 库：seckill_user，本服务独占，其他服务禁止直连本库表
CREATE TABLE t_user (
  id          BIGINT UNSIGNED NOT NULL COMMENT '用户ID(雪花)',
  username    VARCHAR(64)  NOT NULL COMMENT '登录名',
  password    VARCHAR(128) NOT NULL COMMENT 'BCrypt密文',
  nickname    VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
  phone       VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
  status      TINYINT NOT NULL DEFAULT 1 COMMENT '1正常 0封禁',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';
