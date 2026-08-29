package com.ywy.interviewagentapplication.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * CORS 跨域属性配置
 * 设计原因：
 *   1. @ConfigurationProperties(prefix = "app.cors") → 绑定 application.yml 中 app.cors.*
 *   2. 默认值 "http://localhost:5173" — Vite 默认开发端口
 *   3. 支持逗号分隔多个源：通过环境变量 CORS_ALLOWED_ORIGINS 覆盖（见 application.yml）
 * 被依赖方：CorsConfig（第 8 步）
 * 字段：
 *   allowedOrigins — 逗号分隔的允许源列表
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    /**
     * 允许的跨域源地址列表（逗号分隔）
     * 默认值：Vite 前端开发服务器地址
     * 如果上线使用 nginx 服务器，则需要放行 80 端口 和 nginx 服务器 ip
     */
    private String allowedOrigins;
}