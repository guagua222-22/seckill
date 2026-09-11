package com.seckill.user.service;

import com.seckill.user.dto.LoginDTO;
import com.seckill.user.dto.RegisterDTO;
import com.seckill.user.vo.UserVO;

public interface UserService {

    UserVO register(RegisterDTO dto);

    UserVO login(LoginDTO dto);
}
