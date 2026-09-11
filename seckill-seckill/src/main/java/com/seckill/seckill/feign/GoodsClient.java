package com.seckill.seckill.feign;

import com.seckill.common.api.goods.ActivityInfoDTO;
import com.seckill.common.api.goods.DeductStockRequest;
import com.seckill.common.api.goods.RollbackStockRequest;
import com.seckill.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * goods-service 的 Feign 客户端（拆分后的跨域调用入口，替代原 GoodsMapper/StockMapper/
 * SeckillActivityMapper 的直连访问——微服务铁律：跨服务只走对方接口，不碰对方数据库）。
 *
 * 所有端点由 goods 的 InternalGoodsController 提供，路径前缀 /internal，
 * 网关不路由这些路径，Feign 走 lb:// 直连（内部流量不打网关，少一跳）。
 */
@FeignClient(name = "goods-service")
public interface GoodsClient {

    /** 商品名快照：组装 MQ 消息用，商品不存在时 data 为 null */
    @GetMapping("/internal/goods/{id}/name")
    Result<String> goodsName(@PathVariable("id") Long goodsId);

    /** 活动信息：Redis 活动缓存 miss 时兜底；对账任务判断活动是否结束 */
    @GetMapping("/internal/activity/{id}")
    Result<ActivityInfoDTO> activity(@PathVariable("id") Long activityId);

    /** 补预热：Lua 返回 -3（未预热）时触发，goods 侧自己查库预热 */
    @PostMapping("/internal/activity/{id}/preheat")
    Result<Void> preheat(@PathVariable("id") Long activityId);

    /** 条件扣减 DB 库存（requestId 幂等） */
    @PostMapping("/internal/stock/deduct")
    Result<Void> deductStock(@RequestBody DeductStockRequest request);

    /** 回滚 DB 库存（幂等，插单失败补偿/对账兜底共用） */
    @PostMapping("/internal/stock/rollback")
    Result<Void> rollbackStock(@RequestBody RollbackStockRequest request);
}
