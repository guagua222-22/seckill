package com.seckill.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_order")
public class Order {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String orderNo;

    private Long userId;

    private Long activityId;

    private Long goodsId;

    private String goodsName;

    private BigDecimal price;

    private Integer status;

    private String requestId;

    private LocalDateTime createTime;

    private LocalDateTime payTime;
}
