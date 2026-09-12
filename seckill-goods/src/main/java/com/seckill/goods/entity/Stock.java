package com.seckill.goods.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存实体，映射 t_stock 表。
 * 设计要点（面试考点）：库存与商品详情分表存储——秒杀时库存行是超高热点行
 * （每笔订单都要 UPDATE 它），和商品详情读写分离能避免行锁把详情查询一起拖垮。
 * version 字段预留给乐观锁演示。
 */
@Data
@TableName("t_stock")
public class Stock {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 与 t_goods 一对一，唯一索引 uk_goods 保证一个商品只有一行库存 */
    private Long goodsId;

    /** 商品名快照(反范式)：配置行语义，商品改名时由 GoodsServiceImpl.update 同步刷新 */
    private String goodsName;

    /** 活动总库存（活动创建时灌入） */
    private Integer totalStock;

    /** 可用库存——防超卖条件更新就作用在这个字段上 */
    private Integer availableStock;

    /** 已售数量（每次扣减时 +1，用于对账） */
    private Integer soldCount;

    /** 乐观锁版本号（M2 用条件更新已能防超卖，此字段备用演示） */
    private Integer version;

    private LocalDateTime updateTime;
}
