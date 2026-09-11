package com.seckill.seckill;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * seckill-service 启动类（M5 微服务拆分，端口 8083，库 seckill_seckill，
 * 含 order 域与前端验证台静态页）。
 *
 * scanBasePackages 显式带上 com.seckill.common（GlobalExceptionHandler/JacksonConfig/
 * MybatisPlusConfig 都在 common 模块，不显式扫描不会生效）。
 *
 * @MapperScan 覆盖 com.seckill.seckill.order.mapper（order 域并入本服务）。
 * @EnableFeignClients：GoodsClient/UserClient 在本包 feign 子包。
 * @EnableScheduling：MessageResendJob（30s 补偿重发）+ RecordReconcileJob（1h 对账）。
 */
@SpringBootApplication(scanBasePackages = {"com.seckill.common", "com.seckill.seckill"})
@MapperScan("com.seckill.seckill.**.mapper")
@EnableFeignClients(basePackages = "com.seckill.seckill.feign")
@EnableScheduling
public class SeckillServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeckillServiceApplication.class, args);
    }
}
