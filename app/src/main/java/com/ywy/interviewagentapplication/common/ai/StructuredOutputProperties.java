package com.ywy.interviewagentapplication.common.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 项目规范：所有配置统一使用 @ConfigurationProperties，禁止在 Service 中散落 @Value。
 * 结构化输出配置，控制 StructuredOutputInvoker 的重试和修复行为
 * 绑定前缀：app.ai
 * 设计要点：
 *   - maxAttempts=2：最多尝试 2 次（1 次原始 + 1 次修复重试）
 *   - includeLastError=true：重试时把上次错误注入 Prompt，帮 LLM 定向修复
 *   - schemaValidationEnabled=true：优先使用 Spring AI entity schema validation
 */
@SuppressWarnings("ConfigurationProperties")
@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class StructuredOutputProperties {

    /** 结构化输出最大尝试次数（>=1） */
    private int structuredMaxAttempts = 2;
    /** 重试时是否使用修复型提示词（不启用则为简单重试，不会在提示词中追加错误信息或修复说明） */
    private boolean structuredRetryUseRepairPrompt = true;
    /** 修复型重试提示词中是否追加上次错误原因 */
    private boolean structuredIncludeLastError = true;
    /** 修复型重试提示词中是否追加严格 JSON 指令 */
    private boolean structuredRetryAppendStrictJsonInstruction = true;
    /** 注入重试提示词的错误信息最大长度（字符） */
    private int structuredErrorMessageMaxLength = 200;
    /** 是否启用结构化输出指标（Prometheus） */
    private boolean structuredMetricsEnabled = true;
    /** 是否启用 Spring AI schema validation（失败时仍受 maxAttempts 控制） */
    private boolean structuredSchemaValidationEnabled = true;
}
