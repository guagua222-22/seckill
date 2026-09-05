package com.seckill.goods.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_stock")
public class Stock {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long goodsId;

    private Integer totalStock;

    private Integer availableStock;

    private Integer soldCount;

    private Integer version;

    private LocalDateTime updateTime;
}
