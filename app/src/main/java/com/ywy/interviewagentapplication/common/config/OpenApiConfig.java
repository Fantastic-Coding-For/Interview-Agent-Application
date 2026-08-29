package com.ywy.interviewagentapplication.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc OpenAPI / Swagger 文档配置
 * 设计原因：
 * 1. 自动生成交互式 API 文档，方便前后端联调
 * 2. application.yml 中 springdoc.packages-to-scan 限定扫描范围
 * 3. 访问路径：http://localhost:8080/swagger-ui.html
 */
@Configuration
public class OpenApiConfig {

    // 自定义 API 文档的元信息
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("智能 AI 面试官平台 API")               // Swagger UI 标题
                        .description("简历分析、模拟面试、知识库管理 RESTful API 文档")
                        .version("1.0.0"));
    }
}
