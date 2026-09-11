-- M5 微服务拆分：三个业务库（同一 MySQL 8.0.36 实例，宿主端口 3307）
-- 执行方式（一次性）：docker exec -i seckill-mysql mysql -uroot -proot123 < scripts/db/init-databases.sql
-- 说明：现有 mysql-data 卷已初始化，docker-entrypoint-initdb.d 只在空卷首次启动时执行，
--       所以这里用手动执行而非挂载初始化脚本。
-- 各服务的 Flyway 只管理自己库里的表，互不越界。
CREATE DATABASE IF NOT EXISTS seckill_user    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS seckill_goods   DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS seckill_seckill DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
