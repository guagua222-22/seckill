package com.seckill.goods.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class GoodsDTO {

    @NotBlank(message = "商品名称不能为空")
    @Size(max = 128, message = "商品名称最长128个字符")
    private String goodsName;

    @Size(max = 512, message = "商品描述最长512个字符")
    private String description;

    @NotNull(message = "日常价不能为空")
    @DecimalMin(value = "0.01", message = "日常价必须大于0")
    private BigDecimal normalPrice;
}
