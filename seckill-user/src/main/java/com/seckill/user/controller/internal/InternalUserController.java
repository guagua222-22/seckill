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
 * 本控制器是 user 域对外的唯一"数据出口"，seckill-service 的用户校验与用户名快照
 * 只能通过这里的内部端点完成。
 */
@RestController
@RequestMapping("/internal/user")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserMapper userMapper;

    /**
     * 用户名查询：seckill 下单链路用——一次调用同时完成
     * "存在性校验（data 为 null 即不存在）+ 取用户名快照（订单/流水冗余字段）"。
     */
    @GetMapping("/{id}/username")
    public Result<String> username(@PathVariable Long id) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getId, id));
        return Result.ok(user == null ? null : user.getUsername());
    }
}
