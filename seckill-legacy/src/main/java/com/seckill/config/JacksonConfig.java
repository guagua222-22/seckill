package com.seckill.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 全局序列化配置。
 * 雪花算法生成的主键是 19 位 Long，超过 JavaScript Number 的安全整数上限
 * 2^53-1（约 16 位），若以 JSON 数字返回，浏览器 JSON.parse 会丢失末位精度：
 * 页面显示的 ID 与数据库不一致，复制回传还会导致后端查无此记录。
 * 统一把 Long 序列化为字符串，前端原样展示与回传；
 * 反序列化时 Jackson 会自动把数字字符串转回 Long，业务代码无感知。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            // Long.class 覆盖包装类型（实体主键字段），Long.TYPE 覆盖基本类型（如分页的 total）
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
        };
    }
}
