package com.seckill.common.redis;

/**
 * Redis Key 统一命名工具。
 * 所有 key 都从这里生成，避免各处拼接导致格式不一致、难以维护。
 * 命名规则：{业务}:{对象}:{id}，冒号分隔天然形成层级，便于在 redis-cli 里用通配符管理。
 *
 * 微服务拆分后的跨服务契约（重要）：
 * seckill:stock / seckill:user:set / seckill:activity 三个 key 由 goods-service 写入（预热/对账）、
 * seckill-service 读写（Lua 预扣/回滚/查活动），key 格式与序列化格式必须双方一致，
 * 修改本类时同步通知两个服务。
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 秒杀库存计数（Lua DECR 的对象，goods 预热时 SETNX 写入） */
    public static String stock(Long activityId) {
        return "seckill:stock:" + activityId;
    }

    /** 已抢到资格的用户集合（Lua SADD 登记，一人一单判重） */
    public static String userSet(Long activityId) {
        return "seckill:user:set:" + activityId;
    }

    /** 活动信息缓存（时间窗、价格，goods 预热时写入，TTL 到活动结束后 1 小时；
     *  seckill-service 下单链路读它，避免每单都跨服务调 goods） */
    public static String activityInfo(Long activityId) {
        return "seckill:activity:" + activityId;
    }

    /** 商品详情缓存（带逻辑过期字段，防击穿方案见 GoodsServiceImpl） */
    public static String goodsInfo(Long goodsId) {
        return "goods:info:" + goodsId;
    }

    /** 缓存重建互斥锁（击穿防护：SETNX 抢重建权） */
    public static String rebuildLock(Long goodsId) {
        return "lock:rebuild:" + goodsId;
    }
}
