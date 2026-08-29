package com.ywy.interviewagentapplication.modules.resume.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * <h1>简历分析配置属性</h1>
 *
 * <p>从 Spring 配置文件（application.yml）中读取简历 AI 分析相关的配置项。</p>
 *
 * <p>配置前缀：{@code app.resume.analysis}</p>
 *
 * <h2>配置示例（application.yml）</h2>
 * <pre>
 * app:
 *   resume:
 *     analysis:
 *       system-prompt-path: classpath:prompts/resume-analysis-system.st
 *       user-prompt-path: classpath:prompts/resume-analysis-user.st
 * </pre>
 * <h2>默认值</h2>
 * <p>两个路径均有默认值指向类路径下的 {@code .st} 文件（StringTemplate 格式）。
 * 如果需要自定义提示词内容，只需在配置文件中覆盖相应路径。</p>
 *
 * @see ResumeGradingService 使用本配置加载模板的评分服务
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.resume.analysis")
public class ResumeAnalysisProperties {

    private String systemPromptPath = "classpath:prompts/resume-analysis-system.st";
    private String userPromptPath = "classpath:prompts/resume-analysis-user.st";
}

