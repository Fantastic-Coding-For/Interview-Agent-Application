package com.ywy.interviewagentapplication.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * 项目规范：所有配置统一使用 @ConfigurationProperties，禁止在 Service 中散落 @Value。
 * 应用配置属性，绑定 application.yml 中 app.resume.*
 * 被依赖方：ResumeUploadService（校验文件类型）、ResumeParseService（临时文件路径）
 */
@Component
@ConfigurationProperties(prefix = "app.resume")
@Getter
@Setter
public class AppConfigProperties {

    /** 文件上传临时目录（来自 application.yml 的 app.resume.upload-dir） */
    private String uploadDir;
    /** 允许的文件类型白名单（MIME type） */
    private List<String> allowedTypes;
}
