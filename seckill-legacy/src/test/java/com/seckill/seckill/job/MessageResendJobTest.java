package com.seckill.seckill.job;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.seckill.entity.LocalMessage;
import com.seckill.seckill.mapper.LocalMessageMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发送补偿任务单测：重发成功推进状态、失败退避、重试耗尽转终态。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageResendJobTest {

    @Mock
    private LocalMessageMapper messageMapper;
    @Mock
    private RocketMQTemplate rocketMQTemplate;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @InjectMocks
    private MessageResendJob resendJob;

    private LocalMessage pending(int retryCount) throws Exception {
        LocalMessage message = new LocalMessage();
        message.setId(1L);
        message.setMessageId("req-1");
        message.setTopic("seckill-order-topic");
        message.setBody("{\"requestId\":\"req-1\",\"userId\":1,\"activityId\":100}");
        message.setStatus(0);
        message.setRetryCount(retryCount);
        message.setNextRetryTime(LocalDateTime.now().minusMinutes(1));
        return message;
    }

    @Test
    @DisplayName("重发成功：消息置已发送")
    void resendSuccess() throws Exception {
        when(messageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(pending(0)));

        resendJob.run();

        ArgumentCaptor<LocalMessage> captor = ArgumentCaptor.forClass(LocalMessage.class);
        verify(messageMapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getStatus());
    }

    @Test
    @DisplayName("重发失败：重试次数 +1 并退避")
    void resendFailBackoff() throws Exception {
        when(messageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(pending(2)));
        doThrow(new RuntimeException("broker down"))
                .when(rocketMQTemplate).syncSend(anyString(), any(Object.class));

        resendJob.run();

        ArgumentCaptor<LocalMessage> captor = ArgumentCaptor.forClass(LocalMessage.class);
        verify(messageMapper).updateById(captor.capture());
        assertEquals(0, captor.getValue().getStatus()); // 仍是待发送
        assertEquals(3, captor.getValue().getRetryCount()); // 2 + 1
    }

    @Test
    @DisplayName("重试耗尽：置失败终态（告警位）")
    void resendExhausted() throws Exception {
        when(messageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(pending(9)));
        doThrow(new RuntimeException("broker down"))
                .when(rocketMQTemplate).syncSend(anyString(), any(Object.class));

        resendJob.run();

        ArgumentCaptor<LocalMessage> captor = ArgumentCaptor.forClass(LocalMessage.class);
        verify(messageMapper).updateById(captor.capture());
        assertEquals(3, captor.getValue().getStatus()); // 失败终态
    }
}
