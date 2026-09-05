package com.seckill.goods.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_seckill_activity")
public class SeckillActivity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String activityName;

    private Long goodsId;

    private BigDecimal seckillPrice;

    private Integer totalStock;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer status;

    private Integer isHot;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
