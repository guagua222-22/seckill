package com.seckill.user.controller.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.seckill.common.result.Result;
import com.seckill.user.entity.User;
import com.seckill.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户域内部接口（供 seckill-service 的 Feign 调用，网关不路由 /internal/**）。
 *
 * 设计约定：跨服务只走对方的 service/controller 接口，禁止跨服务访问 mapper——
 * 本控制器是 user 域对外的唯一"数据出口"，seckill-service 的用户存在性校验
 * 只能通过 GET /internal/user/{id}/exists 完成。
 */
@RestController
@RequestMapping("/internal/user")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserMapper userMapper;

    /** 用户存在性校验：seckill 下单链路用，Feign 直连不经网关 */
    @GetMapping("/{id}/exists")
    public Result<Boolean> exists(@PathVariable Long id) {
        boolean exists = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getId, id)) != null;
        return Result.ok(exists);
    }
}
