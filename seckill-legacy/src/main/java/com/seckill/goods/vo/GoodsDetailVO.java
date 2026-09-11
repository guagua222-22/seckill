package com.seckill.goods.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class GoodsDetailVO {

    private Long id;

    private String goodsName;

    private String description;

    private BigDecimal normalPrice;

    private Integer status;

    private Integer totalStock;

    private Integer availableStock;

    private Integer soldCount;

    private LocalDateTime createTime;
}
