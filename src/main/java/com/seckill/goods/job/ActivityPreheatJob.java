package com.seckill.goods.job;

import com.seckill.goods.service.ActivityPreheatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 活动预热定时任务。
 * 每 30 秒扫描一次"开始前 5 分钟窗口内或进行中"的活动并预热，
 * 保证秒杀开始前 Redis 库存一定就位（最坏情况下延迟不超过 30 秒 + 补预热兜底）。
 * 提前预热是为了避免"开始瞬间流量尖峰把预热逻辑打穿"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityPreheatJob {

    private final ActivityPreheatService preheatService;

    @Scheduled(fixedDelay = 30_000, initialDelay = 5_000)
    public void run() {
        preheatService.preheatUpcoming();
    }
}
