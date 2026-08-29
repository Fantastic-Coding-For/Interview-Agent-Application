package com.ywy.interviewagentapplication.common.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.Timeout;
import com.openai.credential.BearerTokenCredential;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * OpenAI 兼容 API 的路径统一解析器
 * 设计原因：
 *   1. 不同 LLM Provider API 的 base URL 格式不同（有无版本号、有无尾部斜杠）
 *   2. 统一逻辑：检测 URL 是否含 /vN 版本号 → 不含则追加 /v1
 * 被依赖方：LlmProviderRegistry（每次创建 ChatModel / EmbeddingModel 时调用）
 * 超时配置：
 *   connectTimeout=10s  — TCP 连接建立超时
 *   readTimeout=300s    — 等待 AI 完整响应超时（长文本生成可能需要几分钟）
 */
public final class ApiPathResolver {

    private static final int DEFAULT_CONNECT_TIMEOUT = 10000;   // 10 秒
    private static final int DEFAULT_READ_TIMEOUT = 300000;     // 5 分钟

    /** 匹配末尾版本号：/v1, /v2beta, /v3alpha 等 */
    // `\\d` -> `\d` -> [0-9]; + 至少出现 1 次; * 至少出现 0 次; $ 行尾定位符
    private static final Pattern TRAILING_VERSION = Pattern.compile("/v\\d+[a-zA-Z0-9]*$");

    private ApiPathResolver() {}

    /** 使用默认超时时间创建 OpenAIClient */
    public static OpenAIClient buildOpenAiClient(String baseUrl, String apiKey) {
        return buildOpenAiClient(baseUrl, apiKey, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    /** 创建 OpenAIClient，所有兼容 OPenAI API 的大模型统一调用入口 */
    public static OpenAIClient buildOpenAiClient(String baseUrl, String apiKey,
                                                 int connectTimeout, int readTimeout) {
        // 构建超时对象
        Timeout timeout = Timeout.builder()
                .connect(Duration.ofMillis(connectTimeout))
                .read(Duration.ofMillis(readTimeout))
                .build();
        // 构建 ClientOptions：API Key + Bearer Token + 解析后的 Base URL
        ClientOptions options = ClientOptions.Companion.builder()
                .apiKey(apiKey)
                .credential(BearerTokenCredential.create(apiKey))
                .baseUrl(resolveVersionedBaseUrl(baseUrl))   // 核心逻辑：版本号处理
                .timeout(timeout)
                .httpClient(SpringAiOpenAiHttpClient.builder().timeout(timeout).build())
                .build();
        return new OpenAIClientImpl(options);
    }

    /**
     * 去掉 Base URL 尾部的所有 '/'（如果存在），并添加版本号（如果没有）
     * 输入："https://api.deepseek.com" → 输出："https://api.deepseek.com/v1"
     * 输入："https://dashscope.aliyuncs.com/compatible-mode/v1" → 输出：不变（已有 /v1）
     */
    public static String resolveVersionedBaseUrl(String baseUrl) {
        String stripped = stripTrailingSlashes(baseUrl);
        if (baseUrlContainsVersion(stripped)) {
            return stripped;            // 已有版本号，直接返回
        }
        return stripped + "/v1";        // 追加 /v1
    }

    /** 判断 URL 是否已包含版本号 */
    public static boolean baseUrlContainsVersion(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return false;
        String stripped = stripTrailingSlashes(baseUrl.trim());
        return TRAILING_VERSION.matcher(stripped).find();
    }

    /** 去除 Base URL 末尾所有斜杠 */
    public static String stripTrailingSlashes(String value) {
        if (value == null) return "";
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
