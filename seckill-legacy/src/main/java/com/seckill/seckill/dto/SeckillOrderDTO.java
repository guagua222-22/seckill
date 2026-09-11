package com.seckill.seckill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SeckillOrderDTO {

    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @NotNull(message = "活动ID不能为空")
    private Long activityId;

    @NotBlank(message = "请求ID不能为空")
    @Size(max = 64, message = "请求ID最长64个字符")
    private String requestId;
}
