package com.seckill.seckill.service.impl;

import com.seckill.seckill.entity.LocalMessage;
import com.seckill.seckill.entity.SeckillRecord;
import com.seckill.seckill.mapper.LocalMessageMapper;
import com.seckill.seckill.mapper.SeckillRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 流水与本地消息写入器（独立 Bean，事务原因同 DbOrderWriter）。
 *
 * 本地消息表模式的关键：业务流水与消息记录在【同一事务】中落库——
 * 要么都成功，要么都回滚。事务提交后应用再异步发 MQ：
 * 发成功 → 消息置"已发送"；发失败/发之前宕机 → 消息留在"待发送"，
 * 由补偿任务（MessageResendJob）兜底重发。这就是"至少一次"送达的可靠消息模式。
 */
@Service
@RequiredArgsConstructor
public class RecordMessageWriter {

    private final SeckillRecordMapper recordMapper;
    private final LocalMessageMapper messageMapper;

    /**
     * @param requestId    幂等键
     * @param userId       用户
     * @param username     用户名快照（反范式冗余，避免跨库 join）
     * @param activityId   活动
     * @param activityName 活动名快照（反范式冗余）
     * @param topic        MQ 主题
     * @param body         消息体 JSON
     */
    @Transactional
    public void write(String requestId, Long userId, String username,
                      Long activityId, String activityName, String topic, String body) {
        SeckillRecord record = new SeckillRecord();
        record.setRequestId(requestId);
        record.setUserId(userId);
        record.setUsername(username == null ? "" : username);
        record.setActivityId(activityId);
        record.setActivityName(activityName == null ? "" : activityName);
        record.setStatus(0); // 0 = 已预扣（排队中）
        recordMapper.insert(record);

        LocalMessage message = new LocalMessage();
        message.setMessageId(requestId);
        message.setTopic(topic);
        message.setTag("");
        message.setBody(body);
        message.setStatus(0); // 0 = 待发送
        message.setRetryCount(0);
        message.setNextRetryTime(LocalDateTime.now());
        messageMapper.insert(message);
    }
}
