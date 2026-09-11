package com.seckill.goods.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存扣减操作流水，映射 t_stock_operation 表（M5 拆分新增）。
 *
 * 为什么需要这张表：拆分前"扣库存 + 插订单"在同一个本地数据库事务里，
 * 任一步失败整体回滚。拆分后扣库存变成跨服务 Feign 调用，没有共享事务，
 * Feign 超时重试/消息重投都可能造成重复扣减——这张表以 request_id 为幂等键，
 * 保证同一笔下单请求的库存操作只生效一次（扣或回滚都只一次）。
 */
@Data
@TableName("t_stock_operation")
public class StockOperation {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 幂等键：与下单请求的 requestId 一致 */
    private String requestId;

    private Long goodsId;

    private Long activityId;

    /** 扣减数量（固定 1） */
    private Integer amount;

    /** 0 已扣减 / 1 已回滚 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
