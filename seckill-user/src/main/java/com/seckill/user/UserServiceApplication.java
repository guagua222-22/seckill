package com.seckill.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * user-service 启动类（M5 微服务拆分，端口 8081，库 seckill_user）。
 *
 * scanBasePackages 显式带上 com.seckill.common：@SpringBootApplication 默认只扫
 * 启动类所在包，common 模块里的 GlobalExceptionHandler / JacksonConfig /
 * MybatisPlusConfig 不显式声明就不会被扫描生效。
 *
 * @MapperScan 只扫本服务的 mapper（拆分前的 ** 全扫已按服务收窄）。
 */
@SpringBootApplication(scanBasePackages = {"com.seckill.common", "com.seckill.user"})
@MapperScan("com.seckill.user.mapper")
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
