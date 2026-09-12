package com.seckill.user.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.seckill.user.dto.LoginDTO;
import com.seckill.user.dto.RegisterDTO;
import com.seckill.user.vo.UserVO;

public interface UserService {

    UserVO register(RegisterDTO dto);

    UserVO login(LoginDTO dto);

    /** 用户分页列表（验证台前端用户下拉框/用户列表数据源） */
    Page<UserVO> page(int page, int size);
}
