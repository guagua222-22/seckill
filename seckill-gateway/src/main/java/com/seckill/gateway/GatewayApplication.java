package com.seckill.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * gateway 启动类（M5 微服务拆分，统一入口 8080）。
 *
 * 职责边界：只做路由转发，不放业务逻辑、不引业务依赖（webflux 环境，
 * 引入 starter-web 会直接启动失败）。前端验证台静态页由 seckill-service 承载，
 * 网关把 / 与 /index.html 路由到它，/api/** 按前缀分发到对应服务。
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
