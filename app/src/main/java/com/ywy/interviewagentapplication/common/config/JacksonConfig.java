package com.ywy.interviewagentapplication.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson JSON 序列化配置
 * 设计原因：
 *   1. 注册自定义 ObjectMapper Bean，覆盖 Spring Boot 默认配置
 *   2. 会在以下方面进行增强：驼峰命名、LocalDateTime 格式化、null 值不序列化
 * 被依赖方：Spring MVC 的 HttpMessageConverter（自动检测 ObjectMapper Bean）
 */
@Configuration
public class JacksonConfig {

    /**
     * 提供项目全局 ObjectMapper 实例
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
