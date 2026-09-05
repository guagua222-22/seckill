-- 秒杀预扣脚本（M3 核心）
-- 判断已购 → 判断库存 → 扣减 → 登记，四步必须原子：
-- Redis 单线程执行 Lua 脚本天然原子，比"读-判-写"三段式或分布式锁性能高一个量级。
--
-- KEYS[1] = seckill:stock:{activityId}        库存计数（预热时写入）
-- KEYS[2] = seckill:user:set:{activityId}     已抢到资格的用户集合（一人一单）
-- ARGV[1] = userId
--
-- 返回码契约：
--   1  预扣成功
--   -1 该用户已抢到（重复购买）
--   -2 库存不足
--   -3 活动未预热（key 不存在，非法活动或预热任务异常）
if redis.call('EXISTS', KEYS[1]) == 0 then
  return -3
end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
  return -1
end
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock <= 0 then
  return -2
end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 1
