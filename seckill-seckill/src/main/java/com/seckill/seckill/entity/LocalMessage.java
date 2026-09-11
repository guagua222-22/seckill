package com.seckill.seckill.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本地消息表，映射 t_local_message 表。
 * 这是"可靠消息"模式的核心：业务数据与消息记录在同一事务落库，
 * 之后消息发送失败也能被补偿任务扫出来重发，保证"预扣成功就一定能送达消费端"。
 */
@Data
@TableName("t_local_message")
public class LocalMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 消息唯一 ID = requestId，uk_message_id 唯一索引防重复 */
    private String messageId;

    /** MQ 主题 */
    private String topic;

    /** MQ 标签（本项目未用，预留） */
    private String tag;

    /** 消息体 JSON */
    private String body;

    /** 0 待发送 / 1 已发送 / 3 失败终态（重试耗尽，需告警人工介入） */
    private Integer status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 下次可重试时间（补偿任务只扫描到期记录，避免风暴） */
    private LocalDateTime nextRetryTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
