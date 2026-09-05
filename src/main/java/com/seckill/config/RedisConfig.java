package com.seckill.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis 相关配置。
 * StringRedisTemplate 由 Spring Boot 自动装配（默认 Lettuce 客户端，单连接多路复用，无需连接池）。
 * 这里只注册 Lua 脚本 Bean：启动时把脚本解析成 DefaultRedisScript，
 * 执行时 RedisTemplate 会自动做 EVALSHA（首次 EVAL 后缓存 SHA，减少网络传输）。
 */
@Configuration
public class RedisConfig {

    @Bean
    public DefaultRedisScript<Long> seckillPreDeductScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/seckill_pre_deduct.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
