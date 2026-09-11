package com.seckill.goods.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ActivityDTO {

    @NotBlank(message = "活动名称不能为空")
    @Size(max = 128, message = "活动名称最长128个字符")
    private String activityName;

    @NotNull(message = "商品ID不能为空")
    private Long goodsId;

    @NotNull(message = "秒杀价不能为空")
    @DecimalMin(value = "0.01", message = "秒杀价必须大于0")
    private BigDecimal seckillPrice;

    @NotNull(message = "活动库存不能为空")
    @Min(value = 1, message = "活动库存至少为1")
    private Integer totalStock;

    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;

    private Integer isHot;
}
