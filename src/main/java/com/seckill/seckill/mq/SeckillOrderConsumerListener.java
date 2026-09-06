package com.seckill.seckill.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.seckill.dto.SeckillMessage;
import com.seckill.seckill.service.impl.SeckillOrderConsumerService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * RocketMQ 消费监听器（薄封装：解析消息 → 交给可测试的 ConsumerService）。
 *
 * 消费语义：处理成功直接返回（ACK）；
 * 抛异常则框架自动重试（默认 16 次指数退避），重试耗尽进入死信队列 %DLQ%seckill-order-topic，
 * 死信需要告警/人工介入（第四层兜底）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${seckill.mq.topic}",
        consumerGroup = "${seckill.mq.consumer-group}")
public class SeckillOrderConsumerListener implements RocketMQListener<MessageExt> {

    private final ObjectMapper objectMapper;
    private final SeckillOrderConsumerService consumerService;

    @Override
    @SneakyThrows
    public void onMessage(MessageExt ext) {
        String body = new String(ext.getBody(), StandardCharsets.UTF_8);
        SeckillMessage message = objectMapper.readValue(body, SeckillMessage.class);
        consumerService.process(message);
    }
}
