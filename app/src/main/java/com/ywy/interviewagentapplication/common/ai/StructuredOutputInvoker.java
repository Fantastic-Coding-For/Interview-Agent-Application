package com.ywy.interviewagentapplication.common.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 结构化输出调用器，封装了 LLM JSON 输出的调用、重试与修复功能。
 * <p>设计原因：LLM 返回的 JSON 经常不符合预期（Markdown包裹/未转义引号/缺失字段等）。
 * <p>核心功能：
 * <p>1. 统一重试策略：JSON 解析失败 → 将错误信息注入 Prompt → 再次调用（最多 maxAttempts 次）。</p>
 * <p>2. JSON 修复：逐字符扫描修复未转义引号。优先使用 Spring AI schema validation（默认开启），若设置关闭，则使用自定义修复方法。</p>
 * <p>3. 安全加固：每次调用都在 System Prompt 末尾追加 ANTI_INJECTION_INSTRUCTION。</p>
 * <p>4. 指标记录：调用次数/尝试次数/延迟 → Prometheus 指标（可通过配置关闭）。</p>
 * <p>铁律：所有业务的大模型结构化输出调用（调用 LLM，并将 LLM 输出文本转换为指定类型的 Java 对象）必须通过此 Invoker</p>
 *
 * @see PromptSecurityConstants 追加的防注入指令
 */
@Component
public class StructuredOutputInvoker {
    // ===== 重试 Prompt 模板 =====
    private static final String STRICT_JSON_INSTRUCTION = """
            请仅返回可被 JSON 解析器直接解析的 JSON 对象，并严格满足字段结构要求：
            1) 不要输出 Markdown 代码块（如 ```json）。
            2) 不要输出任何解释文字、前后缀、注释。
            3) 所有字符串内引号必须正确转义。
            """;

    // Prometheus 指标名称
    private static final String METRIC_INVOCATIONS = "app.ai.structured_output.invocations";
    private static final String METRIC_ATTEMPTS = "app.ai.structured_output.attempts";
    private static final String METRIC_LATENCY = "app.ai.structured_output.latency";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILURE = "failure";
    private static final int MAX_CONTEXT_TAG_LENGTH = 48;
    private static final Pattern NON_ALNUM_PATTERN = Pattern.compile("[^a-z0-9_]+");
    private static final Pattern MULTI_UNDERSCORE = Pattern.compile("_+");

    private final int maxAttempts;
    private final boolean includeLastErrorInRetryPrompt;
    private final boolean retryUseRepairPrompt;
    private final boolean retryAppendStrictJsonInstruction;
    private final int errorMessageMaxLength;
    private final boolean metricsEnabled;
    private final boolean schemaValidationEnabled;
    private final MeterRegistry meterRegistry;

    public StructuredOutputInvoker(
            StructuredOutputProperties properties,
            @Autowired(required = false) MeterRegistry meterRegistry
    ) {
        this.maxAttempts = Math.max(1, properties.getStructuredMaxAttempts());
        this.includeLastErrorInRetryPrompt = properties.isStructuredIncludeLastError();
        this.retryUseRepairPrompt = properties.isStructuredRetryUseRepairPrompt();
        this.retryAppendStrictJsonInstruction = properties.isStructuredRetryAppendStrictJsonInstruction();
        this.errorMessageMaxLength = Math.max(20, properties.getStructuredErrorMessageMaxLength());
        this.metricsEnabled = properties.isStructuredMetricsEnabled();
        this.schemaValidationEnabled = properties.isStructuredSchemaValidationEnabled();
        this.meterRegistry = meterRegistry;
    }

     /**
     * 执行 LLM 调用，并将 LLM 输出文本转换为指定类型的 Java 对象（带智能重试），是业务方调用 LLM 结构化输出的唯一入口。
     * <p>
     * <b>核心流程</b>：
     * <ol>
     *   <li><b>安全加固</b>：在原始 System Prompt 末尾强制追加防注入指令。</li>
     *   <li><b>重试循环</b>：最多尝试 {@link #maxAttempts} 次（由配置决定）。</li>
     *   <li><b>首次调用</b>：直接调用。</li>
     *   <li><b>重试提示词</b>：若配置允许，则在 System Prompt 中追加 JSON 严格要求和上次的错误信息，引导 LLM 修正输出。否则仍然直接调用</li>
     *   <li><b>指标记录</b>：每次尝试（成功/失败）和最终调用结果（成功/失败）均记录到 Prometheus（若启用）。</li>
     * </ol>
     * <b>异常处理</b>：
     * <ul>
     *   <li>若某次调用成功，立即返回解析结果。</li>
     *   <li>若未达最大尝试次数，仅通过日志记录错误信息，然后进行重试</li>
     *   <li>若所有尝试均失败，抛出 {@link BusinessException}，错误码为传入的 {@code errorCode}，错误消息由 {@code errorPrefix} + 最后一次异常信息组成。</li>
     *   <li>调用方无需关心内部重试细节，仅需处理最终异常或正常结果。</li>
     * </ul>
     *
     * @param chatClient             非 Voice/Plain 变体的 ChatClient
     * @param systemPromptWithFormat 含 JSON Schema 格式指令的 System Prompt
     * @param userPrompt             用户输入文本
     * @param outputConverter        BeanOutputConverter（含目标类的 JSON Schema）
     * @param errorCode              解析全部失败时返回的错误码
     * @param errorPrefix            错误消息前缀
     * @param logContext             日志上下文标识（用于指标 tag）
     * @param log                    SLF4J Logger 实例
     * @return 解析后的目标类实例
     */
    public <T> T invoke(
            ChatClient chatClient,
            String systemPromptWithFormat,
            String userPrompt,
            BeanOutputConverter<T> outputConverter,
            ErrorCode errorCode,
            String errorPrefix,
            String logContext,
            Logger log
    ) {
        long startNanos = System.nanoTime();
        String contextTag = normalizeContextTag(logContext);
        // 在 System Prompt 末尾追加防注入指令
        String securedSystemPrompt = systemPromptWithFormat
                + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // 首次用原始 Prompt，重试时追加修复指令
            String attemptSystemPrompt = attempt == 1
                    ? securedSystemPrompt
                    : buildRetrySystemPrompt(securedSystemPrompt, lastError);
            try {
                // 执行单次 LLM 调用并尝试将输出转换为目标类型
                T result = callStructuredOutput(
                        chatClient, attemptSystemPrompt, userPrompt, outputConverter, logContext, log);
                recordAttempt(contextTag, STATUS_SUCCESS);
                recordInvocation(contextTag, STATUS_SUCCESS, startNanos);
                return result;
            } catch (Exception e) {
                lastError = e;
                recordAttempt(contextTag, STATUS_FAILURE);
                if (attempt < maxAttempts) {
                    log.warn("{}结构化解析失败，准备重试: attempt={}/{}, error={}",
                            logContext, attempt, maxAttempts, e.getMessage());
                } else {
                    log.error("{}结构化解析失败，已达最大重试次数: attempts={}, error={}",
                            logContext, maxAttempts, e.getMessage());
                }
            }
        }

        // 所有尝试失败 → 抛出业务异常（上层由全局异常处理器转换为 Result.error）
        recordInvocation(contextTag, STATUS_FAILURE, startNanos);
        throw new BusinessException(
                errorCode,
                errorPrefix + (lastError != null ? lastError.getMessage() : "unknown")
        );
    }

    // ===== 私有方法：调用、修复、重试 =====

    /**
     * 执行单次 LLM 调用并尝试将输出转换为目标 Java 对象。
     * <p>LLM 输出转换为目标类型前，会根据配置选择使用 Spring AI 原生方法，或自定义方法校验 Json 格式的有效性。</p>
     *
     * <p>对于Spring AI 原生方法：validateSchema() 会将 LLM 输出与指定的目标类型（如 ActorsFilms.class）的 JSON Schema 进行比对。
     * 校验成功，entity() 会通过指定的 {@code BeanOutputConverter<T>} 将 LLM 输出转换为目标 Java 对象。
     * 校验失败，Spring ai 会将校验错误信息追加到用户提示词（User Prompt）中，然后重新向模型发起一次新的调用。最多 3 次重试。重试耗尽仍校验失败，则抛出运行时异常。</p>
     * <p>对于自定义方法：先尝试直接转换为目标 Java 对象。若抛出异常，则使用自定义的 JSON 格式修复方法尝试修复 LLM 输出文本的格式。再次尝试转换，成功则直接返回，若仍抛出异常，冒泡给上层处理（修复异常添加到转换异常中）</p>
     * @param chatClient       聊天客户端
     * @param systemPrompt     安全加固后的系统提示（已追加防注入指令）
     * @param userPrompt       用户输入
     * @param outputConverter  类型转换器（包含目标类的 JSON Schema）
     * @param logContext       日志上下文（用于日志输出）
     * @param log              SLF4J 日志实例
     * @param <T>              目标类型
     * @return 转换后的目标对象
     * @throws RuntimeException 如果调用失败或解析失败，抛出异常（由上层重试）
     */
    private <T> T callStructuredOutput(
            ChatClient chatClient,
            String systemPrompt,
            String userPrompt,
            BeanOutputConverter<T> outputConverter,
            String logContext,
            Logger log
    ) {
        var call = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call();
        // 优先使用 Spring AI schema validation（更准确的错误定位，默认开启，可通过外部配置关闭）
        if (schemaValidationEnabled) {
            return call.entity(outputConverter, spec -> spec.validateSchema());
        }
        // 否则，自定义 解析 + JSON 修复
        String content = call.content();
        return convertWithRepair(content, outputConverter, logContext, log);
    }

    /**
     * 将 LLM 调用输出的文本内容转换为目标 Java 对象。
     * <p>先尝试直接转换为目标 Java 对象。若抛出异常，则使用自定义的 JSON 格式修复方法尝试修复 LLM 输出文本的格式。再次尝试转换，成功则直接返回，若仍抛出异常，冒泡给上层处理（修复异常添加到转换异常中）</p>
     */
    private <T> T convertWithRepair(
            String content,
            BeanOutputConverter<T> outputConverter,
            String logContext,
            Logger log
    ) {
        try {
            return outputConverter.convert(content);
        } catch (Exception firstError) {
            String repaired = repairUnescapedQuotesInJsonStrings(content);
            if (!repaired.equals(content)) {
                try {
                    T result = outputConverter.convert(repaired);
                    log.warn("{}结构化 JSON 存在未转义引号，已在本地修复后解析成功", logContext);
                    return result;
                } catch (Exception repairError) {
                    firstError.addSuppressed(repairError);
                }
            }
            throw firstError;
        }
    }

    /**
     * 修复 JSON 字符串中的 ""字符串内部的未转义双引号。
     * <p>
     * 算法：遍历 JSON 字符串
     * <p>- 在字符串外：遇到 " → 进入字符串
     * <p>- 在字符串内：
     * <p>- 遇到 \\ → 下一个字符是转义序列，跳过
     * <p>- 遇到 " → 判断后面紧跟的是否是 , } ] :（JSON 终止符）
     * <p>  - 是 → 正常字符串终止
     * <p>  - 否 → 内容中的引号，添加反斜杠转义
     * @param content 原始 JSON 字符串（可能包含未转义引号）
     * @return 修复后的 JSON 字符串，若无需修复则返回原字符串
     */
    private String repairUnescapedQuotesInJsonStrings(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        StringBuilder repaired = new StringBuilder(content.length() + 16);
        boolean inString = false;
        boolean escaping = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (!inString) {
                if (ch == '"') {
                    inString = true;
                }
                repaired.append(ch);
                continue;
            }

            if (escaping) {
                repaired.append(ch);
                escaping = false;
                continue;
            }
            if (ch == '\\') {
                repaired.append(ch);
                escaping = true;
                continue;
            }
            if (ch == '"') {
                if (isLikelyJsonStringTerminator(content, i + 1)) {
                    inString = false;
                    repaired.append(ch);
                } else {
                    repaired.append("\\\"");
                }
                continue;
            }
            repaired.append(ch);
        }
        return repaired.toString();
    }

    /**
     * 判断从 JSON 字符串指定索引开始的首个非空白字符（跳过空白字符）是否为 JSON 字符串的合法终止符（即, } ] :）。
     *
     * @param content 完整的 JSON 字符串
     * @param start   要检查的起始索引（通常为当前双引号的下一个位置）
     * @return 如果后续非空白字符是终止符则返回 true，否则 false
     */
    private boolean isLikelyJsonStringTerminator(String content, int start) {
        for (int i = start; i < content.length(); i++) {
            char next = content.charAt(i);
            if (Character.isWhitespace(next)) {
                continue;
            }
            return next == ',' || next == '}' || next == ']' || next == ':';
        }
        return true;
    }

    /**
     * 构建用于重试的 System Prompt，默认启用修复型提示词：在原始 Prompt 后追加修复说明和上次错误信息（可通过外部配置修改）。
     *
     * @param systemPromptWithFormat 原始的系统提示词（已包含格式指令和防注入指令）
     * @param lastError              上次调用抛出的异常（可能为 null）
     * @return 用于重试的完整 System Prompt
     */
    private String buildRetrySystemPrompt(String systemPromptWithFormat, Exception lastError) {
        // 若不启用修复型提示词，不启用则直接返回原始系统提示词进行重试
        if (!retryUseRepairPrompt) {
            return systemPromptWithFormat;
        }

        StringBuilder prompt = new StringBuilder(systemPromptWithFormat)
                .append("\n\n");

        // 判断是否需要在修复型重试提示词中追加严格 JSON 指令（默认开启，可通过外部配置关闭）
        if (retryAppendStrictJsonInstruction) {
            prompt.append(STRICT_JSON_INSTRUCTION).append('\n');
        }
        // 直接通过语言说明返回合法 JSON，让大模型自己理解
        prompt.append("上次输出解析失败，请仅返回合法 JSON。");

        // 判断是否需要在修复型重试提示词中追加上次错误原因（默认开启，可通过外部配置关闭）
        if (includeLastErrorInRetryPrompt && lastError != null && lastError.getMessage() != null) {
            prompt.append("\n上次失败原因：")
                    .append(sanitizeErrorMessage(lastError.getMessage()));
        }
        return prompt.toString();
    }

    /**
     * 规范化字符串的格式，用于在 LLM 调用重试的 System Prompt 中加入规范化错误信息（去除换行符、限制长度）。
     *
     * @param message 原始错误消息
     * @return 清理后的单行字符串，若超过长度限制则截断并添加省略号
     */
    private String sanitizeErrorMessage(String message) {
        String oneLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() > errorMessageMaxLength) {
            return oneLine.substring(0, errorMessageMaxLength) + "...";
        }
        return oneLine;
    }

    /**
     * 记录一次完整调用 AI 的尝试次数（计数器），包括第 1 次和后面的重试（项目全局累加）。
     * @param contextTag 归一化后的上下文标签（用于区分不同业务场景）
     * @param status     状态，取值为 {@link #STATUS_SUCCESS} 或 {@link #STATUS_FAILURE}
     */
    private void recordAttempt(String contextTag, String status) {
        if (!isMetricsAvailable()) {
            return;
        }
        meterRegistry.counter(
                METRIC_ATTEMPTS,
                Tags.of("context", contextTag, "status", status)
        ).increment();
    }

    /**
     * 记录一次完整调用 AI 的耗时（包括第 1 次和后面的重试花费的总时间），
     * 以及完整调用 AI 的次数（和上面记录的尝试次数不同。不管里面重试了多少次，整体算一次。项目全局累加）。
     * <p> 通过和 recordAttempt 方法协作，从而能够计算出大模型调用的重试率和耗时。
     * @param contextTag 归一化后的上下文标签
     * @param status     最终状态（成功或失败）
     * @param startNanos 调用开始时的系统纳秒时间
     */
    private void recordInvocation(String contextTag, String status, long startNanos) {
        if (!isMetricsAvailable()) {
            return;
        }
        Tags tags = Tags.of("context", contextTag, "status", status);
        meterRegistry.counter(METRIC_INVOCATIONS, tags).increment();
        meterRegistry.timer(METRIC_LATENCY, tags)
                .record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    /**
     * 检查是否启用指标监测功能（默认开启，可通过外部配置修改）。
     * metricsEnabled：是否开启监控
     * meterRegistry：监控系统是否启动
     * @return true 表示可以记录指标，否则 false
     */
    private boolean isMetricsAvailable() {
        return metricsEnabled && meterRegistry != null;
    }

    /**
     * 将字符串归一化为合法格式的 Prometheus 标签值：
     * <ul>
     *   <li>转为小写</li>
     *   <li>替换空格为下划线</li>
     *   <li>移除所有非字母数字下划线的字符</li>
     *   <li>压缩连续下划线为单个</li>
     *   <li>去除首尾下划线</li>
     *   <li>长度限制为 {@value #MAX_CONTEXT_TAG_LENGTH} 字符</li>
     * </ul>
     *
     * @param raw 原始上下文标识（如类名或业务名称）
     * @return 归一化后的标签值，若原始值为空则返回 "unknown"
     */
    private String normalizeContextTag(String raw) {
        String source = (raw == null || raw.isBlank()) ? "unknown" : raw;
        String normalized = source.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
        normalized = NON_ALNUM_PATTERN.matcher(normalized).replaceAll("_");
        normalized = MULTI_UNDERSCORE.matcher(normalized).replaceAll("_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        if (normalized.isBlank()) {
            normalized = "unknown";
        }
        if (normalized.length() > MAX_CONTEXT_TAG_LENGTH) {
            normalized = normalized.substring(0, MAX_CONTEXT_TAG_LENGTH);
        }
        return normalized;
    }
}
