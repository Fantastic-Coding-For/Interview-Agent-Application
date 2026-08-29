package com.ywy.interviewagentapplication;

import org.springframework.ai.model.openai.autoconfigure.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * AI Interview Platform 启动类
 * 设计原因：
 *   1. @EnableScheduling：启用 @Scheduled 定时任务
 *      - QuestionGenerationRecoveryScheduler 定时恢复失败/超时的知识库题目生成任务
 *   2. 排除 OpenAI 自动配置的 6 个类：
 *      - 项目使用自定义 LlmProviderRegistry 管理多个 LLM Provider（DashScope/DeepSeek/Kimi/GLM）
 *      - Spring AI 的 OpenAiAutoConfiguration 假设只有一个 OpenAI-compatible API
 *      - 排除后，在 common/config/LlmProviderProperties 中定义手动管理 Provider Bean
 * 关键决策：
 *   - 不使用 Spring AI 自动配置 → 更灵活的多 Provider 管理
 *   - 不使用 @EnableAsync → 项目统一使用 Redis Stream 做异步而非 @Async 线程池
 */
@EnableScheduling
@SpringBootApplication(exclude = {
        OpenAiAudioSpeechAutoConfiguration.class,        // OpenAI TTS 自动配置
        OpenAiAudioTranscriptionAutoConfiguration.class,  // OpenAI ASR 自动配置
        OpenAiChatAutoConfiguration.class,                // OpenAI ChatClient 自动配置
        OpenAiEmbeddingAutoConfiguration.class,           // OpenAI EmbeddingModel 自动配置
        OpenAiImageAutoConfiguration.class,               // OpenAI 图像生成自动配置
        OpenAiModerationAutoConfiguration.class           // OpenAI 内容审核自动配置
})
public class InterviewAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(InterviewAgentApplication.class, args);
    }
}
