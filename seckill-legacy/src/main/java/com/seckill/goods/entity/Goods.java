package com.seckill.goods.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品实体，映射 t_goods 表。
 * 注意：商品详情与秒杀库存（t_stock）分表，详情接口缓存时也只缓存本表字段，
 * 库存永远以 t_stock/Redis 为准——避免"详情缓存里的库存数字是旧的"这类一致性问题。
 */
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
