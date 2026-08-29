package com.ywy.interviewagentapplication.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;

/**
 * CORS 跨域配置
 * 设计原因：
 *   1. 前后端分离部署时不同源（前端 5173 / 后端 8080），浏览器拦截跨域
 *   2. 使用 Spring 的 CorsFilter 统一添加 Access-Control-Allow-* 响应头
 *   3. 允许源从 CorsProperties 读取（可通过配置动态修改）
 * 设计要点：
 *   - 构造器注入 CorsProperties：不使用 @Value，遵循项目配置规范
 *   - 只匹配 /api/**：避免对静态资源等非 API 路径添加 CORS 头
 *   - 支持 credentials：允许前端携带 Cookie（配合安全认证时使用）
 */
@Configuration
public class CorsConfig {

    private final CorsProperties corsProperties;

    /** 构造器注入：Spring 自动装配 CorsProperties Bean */
    public CorsConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    /**
     * 注册 CorsFilter Bean
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // 逗号分隔字符串 → 逐个添加允许的源地址
        String allowedOrigins = corsProperties.getAllowedOrigins();
        Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .forEach(config::addAllowedOrigin);

        // 允许所有标准 REST 方法
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        // 允许所有请求头
        config.setAllowedHeaders(List.of("*"));
        // 允许携带认证信息（Cookie / Authorization header）
        config.setAllowCredentials(true);
        // 预检请求缓存 1 小时（3600秒），减少不必要的 OPTIONS 请求
        config.setMaxAge(3600L);

        // 只对 /api/** 路径应用 CORS 规则
        // UrlBasedCorsConfigurationSource 是 CorsConfigurationSource 的实现类
        // CorsFilter 在处理每个请求时，会调用它的 CorsConfigurationSource.getCorsConfiguration(request)，
        // 根据当前请求的路径去查找第一个匹配的 CORS 配置，然后返回对应规则
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsFilter(source);
    }
}
