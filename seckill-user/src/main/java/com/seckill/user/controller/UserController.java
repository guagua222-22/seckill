package com.seckill.user.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.seckill.common.result.Result;
import com.seckill.user.dto.LoginDTO;
import com.seckill.user.dto.RegisterDTO;
import com.seckill.user.service.UserService;
import com.seckill.user.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口层。
 * Controller 的职责边界：接收参数（@Valid 校验）、调用 service、包装 Result，
 * 不放任何业务逻辑——业务逻辑都在 service，这是分层的基本功。
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/register")
    public Result<UserVO> register(@Valid @RequestBody RegisterDTO dto) {
        return Result.ok(userService.register(dto));
    }

    @PostMapping("/login")
    public Result<UserVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.ok(userService.login(dto));
    }

    /** 用户分页列表：验证台前端用户列表/抢购下拉框的数据源 */
    @GetMapping("/page")
    public Result<Page<UserVO>> page(@RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return Result.ok(userService.page(page, size));
    }
}
