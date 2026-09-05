package com.seckill.goods.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_goods")
public class Goods {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String goodsName;

    private String description;

    private BigDecimal normalPrice;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
