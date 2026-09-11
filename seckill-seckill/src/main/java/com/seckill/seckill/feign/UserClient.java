package com.seckill.seckill.feign;

import com.seckill.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * user-service 的 Feign 客户端。
 * 下单入口需要校验用户存在性（原 UserMapper.selectById 直连，拆分后只能走接口）。
 */
@FeignClient(name = "user-service")
public interface UserClient {

    /** 用户存在性校验：true 存在 / false 不存在 */
    @GetMapping("/internal/user/{id}/exists")
    Result<Boolean> userExists(@PathVariable("id") Long userId);
}
