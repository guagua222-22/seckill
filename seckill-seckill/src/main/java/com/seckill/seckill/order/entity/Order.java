package com.seckill.seckill.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体，映射 t_order 表。
 * 三个唯一索引是防重复的核心（M2 重点）：
 * - uk_order_no：订单号全局唯一
 * - uk_user_activity：一人一单（业务幂等）
 * - uk_request_id：请求级幂等（网络重试不重复下单）
 * goods_name/price 是下单时的快照：即使商品后来改名/改价，订单上仍是成交时的信息，
 * 也避免查订单时跨表关联商品。
 */
@Data
@TableName("t_order")
public class Order {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String orderNo;

    private Long userId;

    /** 用户名快照(反范式冗余)：下单时从 user-service 取回存下，避免跨库 join，也方便直接看数据表 */
    private String username;

    private Long activityId;

    /** 活动名快照(反范式冗余)：同理，避免跨库 join 才能看出这是哪场秒杀 */
    private String activityName;

    private Long goodsId;

    private String goodsName;

    private BigDecimal price;

    private Integer status;

    private String requestId;

    private LocalDateTime createTime;

    private LocalDateTime payTime;
}
