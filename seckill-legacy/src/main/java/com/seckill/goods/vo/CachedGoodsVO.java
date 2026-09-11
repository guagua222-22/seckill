package com.seckill.goods.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品详情缓存结构。
 * 注意：库存字段（totalStock/availableStock）刻意不进入缓存——
 * 库存是强一致数据，详情接口每次从 t_stock 现查（单行主键查询，成本可接受）；
 * 若把库存缓存起来，出现"缓存里库存有货、实际已卖光"的脏读反而更糟。
 */
@Data
public class CachedGoodsVO {

    /** 逻辑过期时间：物理 TTL 只兜底，业务上以它为准判断是否过期（击穿防护的关键） */
    private LocalDateTime expireAt;

    private Long id;

    private String goodsName;

    private String description;

    private BigDecimal normalPrice;

    private Integer status;

    private LocalDateTime createTime;
}
