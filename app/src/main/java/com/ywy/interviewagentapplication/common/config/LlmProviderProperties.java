package com.ywy.interviewagentapplication.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/**
 * 项目规范：所有配置统一使用 @ConfigurationProperties，禁止在 Service 中散落 @Value。
 * 多 LLM Provider 体系的配置驱动
 * 绑定前缀：app.ai
 * defaultProvider="dashscope"：本地开发默认使用百炼
 * 被依赖方：LlmProviderRegistry（读取 Provider 列表构建 ChatClient）
 */
@SuppressWarnings("ConfigurationProperties")
@Data
@Component
@ConfigurationProperties(prefix = "app.ai")
public class LlmProviderProperties {
    /** 默认聊天模型的 Provider ID */
    private String defaultProvider = "dashscope";
    /** 默认 Embedding 模型的 Provider ID，
     * 如果未配置，将会以默认聊天模型的 Provider ID（defaultProvider）作为默认值 */
    private String defaultEmbeddingProvider;
    /** Embedding 向量维度（text-embedding-v3 = 1024） */
    private Integer embeddingDimensions = 1024;
    /** 所有 Provider 配置统一存放在 Map 中：key=providerId, value=ProviderConfig */
    private Map<String, ProviderConfig> providers;
    /** Advisor 拦截器链配置 */
    private AdvisorConfig advisors = new AdvisorConfig();
    /** Provider 配置 YAML 持久化路径 */
    private String configYamlPath;
    /** Provider 配置 .env 持久化路径 */
    private String configEnvPath;
    /** API Key 加密配置 */
    private SecurityConfig security = new SecurityConfig();

    /** 单个 Provider 的完整配置 */
    @Data
    public static class ProviderConfig {
        private String baseUrl;              // API 端点（如 https://dashscope.aliyuncs.com/compatible-mode/v1）
        private String apiKey;               // API Key（仅 YAML 配置，数据库存密文）
        private String model;                // 默认模型名
        private String embeddingModel;       // Embedding 模型名（可为空）
        private Integer embeddingDimensions; // Embedding 向量维度
        private Boolean supportsEmbedding;   // 是否支持向量化
        private Double temperature;          // 默认采样温度（0~2）
    }

    /** API Key 加密安全配置 */
    @Data
    public static class SecurityConfig {
        private String apiKeyEncryptionKey;        // AES-256 密钥
        private boolean requireEncryptionKey = true; // 是否强制要求配置 AES-256 密钥
        private boolean allowFallbackEncryptionKey = false; // 是否使用备用的密钥
    }

    /** Advisor 拦截器链配置：控制 ChatClient 的行为增强 */
    @Data
    public static class AdvisorConfig {
        private boolean enabled = true;                    // Advisor 总开关，默认开启
        private boolean toolCallEnabled = true;            // 是否启用 ToolCallAdvisor（函数调用），默认开启
        private boolean toolCallConversationHistoryEnabled = false;     // 是否启用 ToolCallAdvisor 的历史会话，默认关闭
        private boolean messageChatMemoryEnabled = false;  // 是否启用 MessageChatMemoryAdvisor（聊天会话记忆），默认关闭
        private int messageChatMemoryMaxMessages = 120;    // 记忆轮数上限
        private boolean simpleLoggerEnabled = false;       // 是否启用 SimpleLoggerAdvisor（调试日志），默认关闭
        private boolean safeguardEnabled = true;           // 是否启用 SafeGuardAdvisor（敏感词拦截），默认开启
        private List<String> safeguardWords = List.of(     // 敏感词列表
                "I'll now act as", "Sure, I'll ignore", "我已经忽略",
                "新的角色是", "忽略之前的指令", "forget all previous instructions"
        );
        private boolean promptSanitizerEnabled = true;     // 是否启用 PromptSanitizer（提示词清洗），默认开启
    }
}
