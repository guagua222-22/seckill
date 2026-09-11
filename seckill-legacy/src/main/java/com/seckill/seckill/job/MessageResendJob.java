package com.seckill.seckill.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.seckill.dto.SeckillMessage;
import com.seckill.seckill.entity.LocalMessage;
import com.seckill.seckill.mapper.LocalMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 发送补偿任务（可靠消息第一层兜底）。
 * 每 30 秒扫描"待发送且已到重试时间"的本地消息并重发：
 * - 成功 → 置"已发送"
 * - 失败 → 重试次数 +1，下次重试时间按线性退避（30s × 次数）延后
 * - 重试满 10 次 → 置失败终态(3)并记 error 日志（生产环境此处接告警）
 *
 * 它兜底的场景：应用发送 MQ 失败、事务提交后但发送前宕机——消息永远能从表里找回。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageResendJob {

    private static final int MAX_RETRY = 10;

    private final LocalMessageMapper messageMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    public void run() {
        List<LocalMessage> pending = messageMapper.selectList(
                new LambdaQueryWrapper<LocalMessage>()
                        .eq(LocalMessage::getStatus, 0)
                        .le(LocalMessage::getNextRetryTime, LocalDateTime.now())
                        .last("LIMIT 200"));

        for (LocalMessage message : pending) {
            try {
                SeckillMessage msg = objectMapper.readValue(message.getBody(), SeckillMessage.class);
                // 同步发送：拿到 broker 的确认才算发送成功，避免"半成功"状态
                rocketMQTemplate.syncSend(message.getTopic(), msg);
                message.setStatus(1);
                messageMapper.updateById(message);
                log.info("补偿重发成功: messageId={}", message.getMessageId());
            } catch (Exception e) {
                int retries = message.getRetryCount() + 1;
                if (retries >= MAX_RETRY) {
                    message.setStatus(3); // 失败终态：告警位
                    log.error("本地消息重试耗尽，置失败终态: messageId={}, err={}",
                            message.getMessageId(), e.getMessage());
                } else {
                    message.setRetryCount(retries);
                    // 线性退避：30s、60s、90s……避免失败消息风暴
                    message.setNextRetryTime(LocalDateTime.now().plusSeconds(30L * retries));
                    log.warn("补偿重发失败，第 {} 次: messageId={}, err={}",
                            retries, message.getMessageId(), e.getMessage());
                }
                messageMapper.updateById(message);
            }
        }
    }
}
