package com.ywy.interviewagentapplication.common.ai;


import com.ywy.interviewagentapplication.common.config.LlmProviderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Prompt 注入净化工具
 * 除了 Layer 1（System Prompt 安全指令），还需要 Layer 2 通过正则表达式对用户输入进行清理。
 * - Layer 1 是"软防御"——依赖 LLM 的理解能力
 * - Layer 2 是"硬防御"——直接替换危险模式，不依赖 LLM
 * 作用范围：仅用于 4 个高风险直接拼接点（裸拼接，无模板包裹），模板插值点有 Layer 1（系统提示词）保护，不需要额外净化
 * 四级检测：
 *   ROLE_INJECTION  → 行首角色标记注入（"system: do X"）
 *   INJECTION_PHRASE → 注入短语（"ignore previous instructions"）
 *   DELIMITER_INJECTION → 分隔符伪造（"---简历内容结束---"）
 *   BOUNDARY_TAG → XML 边界标签伪造（"</data-boundary>"）
 * 安全策略：
 *   - 匹配到注入模式 → 替换为不可执行的占位符 "[filtered]"
 *   - wrapWithDelimiters(): 用 UUID 随机分隔符包裹用户文本，攻击者无法伪造
 */
@Component
public class PromptSanitizer {

    private static final Logger log = LoggerFactory.getLogger(PromptSanitizer.class);

    private final LlmProviderProperties properties;

    public PromptSanitizer(LlmProviderProperties properties) {
        this.properties = properties;
    }

    /**
     * 行首角色标记：只匹配行首，避免误杀 "Experience with system design"
     * (?im)：大小写不敏感 + 多行模式
     * ^：行首
     * \s*：至少出现 0 个白字符
     */
    private static final Pattern ROLE_INJECTION_PATTERN = Pattern.compile(
            "(?im)^\\s*(system|user|assistant|human|ai|model)\\s*[:：].*"
    );

    // 注入短语：精确匹配，不单独匹配 "忽略" 或 "instruction" 等常见词
    private static final Pattern INJECTION_PHRASE_PATTERN = Pattern.compile(
            "(ignore\\s+(previous|above|all|your)\\s*(instructions|prompts|rules))" +
                    "|(forget\\s+(everything|all\\s*(previous\\s*)?(instructions|rules|prompts)))" +
                    "|(new\\s+instructions?:)" +
                    "|忽略之前的指令" +
                    "|忘记之前的指令" +
                    "|忽略以上所有" +
                    "|你不再是" +
                    "|你的新角色是",
            Pattern.CASE_INSENSITIVE
    );

    // 伪造分隔符：匹配项目中 .st 模板使用的静态分隔符
    private static final Pattern DELIMITER_INJECTION_PATTERN = Pattern.compile(
            "---(?:简历|文档|问答)内容(?:开始|结束)---"
    );

    // 伪造 XML 边界标签：防止攻击者构造 <data-boundary...> 来提前关闭包裹
    private static final Pattern BOUNDARY_TAG_PATTERN = Pattern.compile(
            "</?data-boundary[^>]*>", Pattern.CASE_INSENSITIVE
    );

    /**
     * 清洗用户文本，替换危险模式为中性占位符。
     * 受 {@code app.ai.advisors.promptSanitizerEnabled} 配置控制，默认开启
     */
    public String sanitize(String text) {
        if (text == null || text.isBlank()) return text;
        if (!isSanitizerEnabled()) return text;  // 可通过配置关闭

        boolean injected = false;
        String result = text;

        var roleMatcher = ROLE_INJECTION_PATTERN.matcher(result);
        if (roleMatcher.find()) {
            injected = true;
            result = roleMatcher.replaceAll("[filtered-role-marker]");
        }
        var phraseMatcher = INJECTION_PHRASE_PATTERN.matcher(result);
        if (phraseMatcher.find()) {
            injected = true;
            result = phraseMatcher.replaceAll("[filtered]");
        }
        // 分隔符和标签检测——即使未被前两级匹配也要处理（属于结构性攻击）
        var delimMatcher = DELIMITER_INJECTION_PATTERN.matcher(result);
        if (delimMatcher.find()) {
            result = delimMatcher.replaceAll("[filtered-delimiter]");
        }
        var tagMatcher = BOUNDARY_TAG_PATTERN.matcher(result);
        if (tagMatcher.find()) {
            result = tagMatcher.replaceAll("[filtered-boundary-tag]");
        }

        if (injected) {
            log.warn("检测到潜在 Prompt 注入尝试，文本长度: {}", text.length());
        }
        return result;
    }

    /**
     * 通过伪造的不可预测的分隔符包裹用户文本
     * 随机的 UUID 片段使攻击者无法提前构造伪造分隔符来"逃逸"数据边界
     */
    public String wrapWithDelimiters(String label, String text) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String openTag = "<data-boundary-" + id + "-" + label + ">";
        String closeTag = "</data-boundary-" + id + "-" + label + ">";
        return openTag + "\n" + text + "\n" + closeTag;
    }

    /** 检测注入尝试（仅日志告警，不阻断业务） */
    public boolean detectInjectionAttempt(String text) {
        if (text == null || text.isBlank()) return false;
        return ROLE_INJECTION_PATTERN.matcher(text).find()
                || INJECTION_PHRASE_PATTERN.matcher(text).find();
    }

    private boolean isSanitizerEnabled() {
        return properties.getAdvisors() == null
                || properties.getAdvisors().isPromptSanitizerEnabled();
    }
}
