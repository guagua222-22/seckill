package com.seckill.seckill.service.impl;

import com.seckill.seckill.entity.LocalMessage;
import com.seckill.seckill.entity.SeckillRecord;
import com.seckill.seckill.mapper.LocalMessageMapper;
import com.seckill.seckill.mapper.SeckillRecordMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

/**
 * 流水与本地消息写入器单测：流水与消息必须同事务落库（本地消息表模式的关键）。
 */
@ExtendWith(MockitoExtension.class)
class RecordMessageWriterTest {

    @Mock
    private SeckillRecordMapper recordMapper;
    @Mock
    private LocalMessageMapper messageMapper;

    @InjectMocks
    private RecordMessageWriter writer;

    @Test
    @DisplayName("写入：流水（排队中）与本地消息（待发送）同时落库")
    void writeBoth() {
        writer.write("req-1", 1L, 100L, "seckill-order-topic", "{\"requestId\":\"req-1\"}");

        ArgumentCaptor<SeckillRecord> recordCaptor = ArgumentCaptor.forClass(SeckillRecord.class);
        verify(recordMapper).insert(recordCaptor.capture());
        SeckillRecord record = recordCaptor.getValue();
        assertEquals("req-1", record.getRequestId());
        assertEquals(1L, record.getUserId());
        assertEquals(100L, record.getActivityId());
        assertEquals(0, record.getStatus()); // 0 = 已预扣（排队中）

        ArgumentCaptor<LocalMessage> messageCaptor = ArgumentCaptor.forClass(LocalMessage.class);
        verify(messageMapper).insert(messageCaptor.capture());
        LocalMessage message = messageCaptor.getValue();
        assertEquals("req-1", message.getMessageId());
        assertEquals("seckill-order-topic", message.getTopic());
        assertEquals(0, message.getStatus()); // 0 = 待发送
        assertEquals(0, message.getRetryCount());
    }
}
