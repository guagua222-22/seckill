package com.seckill.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 4, max = 32, message = "用户名长度需在4-32之间")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度需在6-64之间")
    private String password;

    @Size(max = 32, message = "昵称最长32个字符")
    private String nickname;

    @Pattern(regexp = "^$|^1\\d{10}$", message = "手机号格式不正确")
    private String phone;
}
