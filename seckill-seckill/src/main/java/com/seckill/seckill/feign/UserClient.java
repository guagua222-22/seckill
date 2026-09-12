package com.seckill.seckill.feign;

import com.seckill.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * user-service 的 Feign 客户端。
 * 拆分后跨服务禁止直连对方数据库，用户信息只能走 user-service 暴露的 /internal 接口。
 */
@FeignClient(name = "user-service")
public interface UserClient {

    /**
     * 按用户 ID 取用户名。
     * 一次调用同时承担两个职责：返回 null 说明用户不存在（等价于原来的存在性校验），
     * 返回非 null 则把用户名带回来作为订单/流水的快照字段，避免查表时跨库 join。
     */
    @GetMapping("/internal/user/{id}/username")
    Result<String> username(@PathVariable("id") Long userId);
}
