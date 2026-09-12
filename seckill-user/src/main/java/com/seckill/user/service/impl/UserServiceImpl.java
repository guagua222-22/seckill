package com.seckill.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.user.dto.LoginDTO;
import com.seckill.user.dto.RegisterDTO;
import com.seckill.user.entity.User;
import com.seckill.user.mapper.UserMapper;
import com.seckill.user.service.UserService;
import com.seckill.user.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户注册/登录实现。
 * 密码安全：BCrypt 是"加盐的慢哈希"，同样的明文每次加密结果都不同，
 * 只能单向比对（matches），无法反解——数据库泄露也不会暴露明文密码。
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public UserVO register(RegisterDTO dto) {
        // 先查重：username 有唯一索引，但主动查一次可以返回友好的业务码
        //（否则要依赖全局异常处理器去翻译 DuplicateKeyException）
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername()));
        if (count > 0) {
            throw new BizException(ErrorCode.USERNAME_EXISTS);
        }
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname());
        user.setPhone(dto.getPhone());
        user.setStatus(1);
        userMapper.insert(user);
        // 回读一次拿到数据库生成的 create_time，避免响应里时间字段为空
        return toVO(userMapper.selectById(user.getId()));
    }

    @Override
    public UserVO login(LoginDTO dto) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername()));
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BizException(ErrorCode.PASSWORD_ERROR);
        }
        return toVO(user);
    }

    @Override
    public Page<UserVO> page(int page, int size) {
        // 新用户排前面，验证台下拉框默认选到最新注册的用户
        Page<User> userPage = userMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<User>().orderByDesc(User::getCreateTime));
        Page<UserVO> voPage = new Page<>(userPage.getCurrent(), userPage.getSize(), userPage.getTotal());
        voPage.setRecords(userPage.getRecords().stream().map(this::toVO).toList());
        return voPage;
    }

    private UserVO toVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setPhone(user.getPhone());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }
}
