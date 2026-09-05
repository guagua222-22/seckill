package com.seckill;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启动类。
 * 包结构按领域划分（user/goods/order/seckill/common/config/data），
 * 为 M5 微服务拆分预留边界：跨域只走对方 service 接口，禁止跨域访问 mapper。
 *
 * @MapperScan 统一扫描各领域的 mapper 包，拆服务时每个服务各自扫描自己的包即可。
 * @EnableScheduling 开启定时任务（M3 起用于活动预热与对账清理）。
 */
@SpringBootApplication
@MapperScan("com.seckill.**.mapper")
@EnableScheduling
public class SeckillApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeckillApplication.class, args);
    }
}
