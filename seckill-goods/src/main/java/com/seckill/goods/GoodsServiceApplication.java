package com.seckill.goods;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * goods-service 启动类（M5 微服务拆分，端口 8082，库 seckill_goods）。
 *
 * scanBasePackages 显式带上 com.seckill.common（GlobalExceptionHandler/JacksonConfig/
 * MybatisPlusConfig 都在 common 模块，不显式扫描不会生效）。
 *
 * @EnableScheduling：本服务有两个定时任务——
 * ActivityPreheatJob（30s 预热即将开始的活动）+ StockReconcileJob（1h 对账）。
 */
@SpringBootApplication(scanBasePackages = {"com.seckill.common", "com.seckill.goods"})
@MapperScan("com.seckill.goods.mapper")
@EnableScheduling
public class GoodsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GoodsServiceApplication.class, args);
    }
}
