package com.seckill.user.service.impl;

import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.user.dto.LoginDTO;
import com.seckill.user.dto.RegisterDTO;
import com.seckill.user.entity.User;
import com.seckill.user.mapper.UserMapper;
import com.seckill.user.vo.UserVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private UserServiceImpl userService;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    @DisplayName("注册成功：密码以 BCrypt 密文入库")
    void registerSuccess() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        AtomicReference<User> saved = new AtomicReference<>();
        doAnswer(inv -> {
            saved.set(inv.getArgument(0));
            return 1;
        }).when(userMapper).insert(any(User.class));
        when(userMapper.selectById(any())).thenAnswer(inv -> saved.get());

        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("alice");
        dto.setPassword("123456");
        dto.setNickname("Alice");

        UserVO vo = userService.register(dto);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User inserted = captor.getValue();
        assertEquals("alice", inserted.getUsername());
        assertNotEquals("123456", inserted.getPassword());
        assertTrue(encoder.matches("123456", inserted.getPassword()));
        assertEquals(1, inserted.getStatus());
        assertNotNull(vo.getUsername());
    }

    @Test
    @DisplayName("注册失败：用户名已存在")
    void registerDuplicate() {
        when(userMapper.selectCount(any())).thenReturn(1L);
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername("alice");
        dto.setPassword("123456");

        BizException e = assertThrows(BizException.class, () -> userService.register(dto));
        assertEquals(ErrorCode.USERNAME_EXISTS.getCode(), e.getCode());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    @DisplayName("登录成功")
    void loginSuccess() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setPassword(encoder.encode("123456"));
        when(userMapper.selectOne(any())).thenReturn(user);

        LoginDTO dto = new LoginDTO();
        dto.setUsername("alice");
        dto.setPassword("123456");

        UserVO vo = userService.login(dto);
        assertEquals("alice", vo.getUsername());
    }

    @Test
    @DisplayName("登录失败：用户不存在")
    void loginUserNotFound() {
        when(userMapper.selectOne(any())).thenReturn(null);
        LoginDTO dto = new LoginDTO();
        dto.setUsername("ghost");
        dto.setPassword("123456");

        BizException e = assertThrows(BizException.class, () -> userService.login(dto));
        assertEquals(ErrorCode.USER_NOT_FOUND.getCode(), e.getCode());
    }

    @Test
    @DisplayName("登录失败：密码错误")
    void loginWrongPassword() {
        User user = new User();
        user.setUsername("alice");
        user.setPassword(encoder.encode("correct"));
        when(userMapper.selectOne(any())).thenReturn(user);

        LoginDTO dto = new LoginDTO();
        dto.setUsername("alice");
        dto.setPassword("wrong");

        BizException e = assertThrows(BizException.class, () -> userService.login(dto));
        assertEquals(ErrorCode.PASSWORD_ERROR.getCode(), e.getCode());
    }
}
