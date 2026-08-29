package com.ywy.interviewagentapplication.modules.voiceinterview.service;

import com.ywy.interviewagentapplication.common.ai.LlmProviderRegistry;
import com.ywy.interviewagentapplication.common.ai.PromptSanitizer;
import com.ywy.interviewagentapplication.modules.resume.model.ResumeEntity;
import com.ywy.interviewagentapplication.modules.resume.repository.ResumeRepository;
import com.ywy.interviewagentapplication.modules.voiceinterview.config.VoiceInterviewProperties;
import com.ywy.interviewagentapplication.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 语音面试的 LLM 调用服务（流式句子切分 + 语音场景文本优化）。
 *
 * <h3>与文本面试 LLM 服务的差异</h3>
 * <ul>
 *   <li><b>文本优化</b>：{@link #optimizeForVoice} 截断过长回复并剥离 Markdown——
 *       面试官的回复要被 TTS 读出来，列表/代码块/加粗符号毫无意义，
 *       且长文本会拖长 TTS 合成与播放时延</li>
 *   <li><b>句子级流式切分</b>：{@link #chatStreamSentences} 在 LLM 流式输出过程中
 *       检测句子边界回调 onSentence，让 TTS 可以「生成一句合成一句」，
 *       与 LLM 生成流水线并行，显著降低首句音频的等待时延</li>
 *   <li><b>实时文本节流</b>：onToken 推送按「间隔 + 最小增量」双条件节流
 *       （emitIntervalMs / minCharsDelta），避免每个 token 都发一条 WebSocket
 *       消息刷爆前端渲染</li>
 * </ul>
 *
 * <h3>错误处理哲学</h3>
 * LLM 调用失败不抛异常，而是返回用户可读的兜底文案
 * （{@link #mapLlmErrorToUserMessage}）——语音场景用户只听得见声音，
 * 把原始异常文本读给用户没有任何意义，按错误特征映射成人话更友好。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DashscopeLlmService {

    /**
     * 句子终止标点集合：检测到这些字符即认为一句话结束，触发句子回调。
     * 包含中英文句号/感叹号/问号/分号。
     */
    private static final String TERMINAL_PUNCTUATION = "。！？；!?;.";

    private final LlmProviderRegistry llmProviderRegistry;
    private final VoiceInterviewPromptService promptService;
    private final ResumeRepository resumeRepository;
    private final VoiceInterviewProperties voiceInterviewProperties;
    private final PromptSanitizer promptSanitizer;

    /**
     * 非流式调用 LLM：完整生成后一次性返回优化文本。
     *
     * @param userInput          候选人本轮回答
     * @param session            会话实体（携带 skillId / resumeId / llmProvider）
     * @param conversationHistory 压缩后的对话历史文本行
     * @return 优化后的面试官回复（失败时返回用户可读的兜底文案）
     */
    public String chat(String userInput, VoiceInterviewSessionEntity session, List<String> conversationHistory) {
        try {
            PromptContext promptContext = buildPromptContext(userInput, session, conversationHistory);

            String provider = session.getLlmProvider();
            log.info("[VoiceInterview] Session {} using LLM provider: {}", session.getId(), provider);

            ChatClient chatClient = llmProviderRegistry.getVoiceChatClient(provider);

            ChatClient.CallResponseSpec response = chatClient.prompt()
                    .system(promptContext.systemPrompt())
                    .user(promptContext.userPrompt())
                    .call();

            String content = response.chatResponse().getResult().getOutput().getText();
            String optimized = optimizeForVoice(content);

            log.info("LLM response generated for session {}: {}", session.getId(),
                    optimized.substring(0, Math.min(100, optimized.length())));

            return optimized;

        } catch (Exception e) {
            log.error("LLM chat error for session {}: {}", session.getId(), e.getMessage(), e);
            return mapLlmErrorToUserMessage(e);
        }
    }

    /**
     * 流式调用（仅文本回调，无句子切分）。等价于
     * {@link #chatStreamSentences} 的 onSentence=null 场景。
     */
    public String chatStream(String userInput, Consumer<String> onToken, VoiceInterviewSessionEntity session, List<String> conversationHistory) {
        return chatStreamSentences(userInput, onToken, null, session, conversationHistory);
    }

    /**
     * 流式调用 LLM，每检测到一个完整句子就回调 onSentence，同时推送实时文本给 onToken。
     * 返回完整优化后的文本。
     *
     * <h3>双回调协议</h3>
     * <ul>
     *   <li><b>onToken</b>：实时文本节流推送（前端实时字幕），可能对同一段文本
     *       推送多次（每次是累计文本的快照），最终再推一次完整优化文本</li>
     *   <li><b>onSentence</b>：句子边界触发（TTS 合成的最小单元），
     *       每个句子只回调一次，且按顺序</li>
     * </ul>
     * 句子游标 lastSentenceEnd 保证句子切分「只增不减」：流式文本是累计追加的，
     * 每次从上一句结束位置截取新句，天然有序无重叠。
     *
     * @param userInput          候选人本轮回答
     * @param onToken            实时文本回调（可为 null）
     * @param onSentence         完整句子回调（可为 null）
     * @param session            会话实体
     * @param conversationHistory 压缩后的对话历史
     * @return 完整优化后的面试官回复文本
     */
    public String chatStreamSentences(String userInput,
                                      Consumer<String> onToken,
                                      Consumer<String> onSentence,
                                      VoiceInterviewSessionEntity session,
                                      List<String> conversationHistory) {
        try {
            PromptContext promptContext = buildPromptContext(userInput, session, conversationHistory);
            String provider = session.getLlmProvider();
            log.info("[VoiceInterview] Session {} using LLM provider (sentence stream): {}", session.getId(), provider);

            ChatClient chatClient = llmProviderRegistry.getVoiceChatClient(provider);
            StringBuilder raw = new StringBuilder();
            AtomicLong lastEmitNanos = new AtomicLong(System.nanoTime());
            AtomicInteger lastEmitLength = new AtomicInteger(0);
            AtomicInteger lastSentenceEnd = new AtomicInteger(0);
            // 节流参数取下限保护：配置过小会导致 WebSocket 刷屏
            int emitIntervalMs = Math.max(80, voiceInterviewProperties.getAiStreamPushIntervalMs());
            int minCharsDelta = Math.max(4, voiceInterviewProperties.getAiStreamMinCharsDelta());

            chatClient.prompt()
                    .system(promptContext.systemPrompt())
                    .user(promptContext.userPrompt())
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        if (token == null || token.isEmpty()) {
                            return;
                        }
                        raw.append(token);

                        // 检测句子边界，回调 onSentence
                        if (onSentence != null && hasTerminalSince(token)) {
                            String normalized = normalizeRealtimeText(raw.toString());
                            int currentEnd = normalized.length();
                            if (currentEnd > lastSentenceEnd.get()) {
                                String sentence = normalized.substring(lastSentenceEnd.get()).trim();
                                if (!sentence.isEmpty()) {
                                    onSentence.accept(sentence);
                                }
                                lastSentenceEnd.set(currentEnd);
                            }
                        }

                        // 实时文本推送
                        if (onToken == null) {
                            return;
                        }
                        long now = System.nanoTime();
                        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(now - lastEmitNanos.get());
                        int currentLength = raw.length();
                        // 双条件节流：间隔足够长 且 增量足够大才推，避免高频小增量刷屏
                        boolean shouldEmit = elapsedMs >= emitIntervalMs && currentLength - lastEmitLength.get() >= minCharsDelta;
                        if (!shouldEmit) {
                            return;
                        }
                        String normalized = normalizeRealtimeText(raw.toString());
                        if (normalized.isBlank()) {
                            return;
                        }
                        onToken.accept(normalized);
                        lastEmitNanos.set(now);
                        lastEmitLength.set(normalized.length());
                    })
                    .blockLast();

            // 发送最后一段（可能不以终止标点结尾）
            if (onSentence != null) {
                String normalized = normalizeRealtimeText(raw.toString());
                if (normalized.length() > lastSentenceEnd.get()) {
                    String remaining = normalized.substring(lastSentenceEnd.get()).trim();
                    if (!remaining.isEmpty()) {
                        onSentence.accept(remaining);
                    }
                }
            }

            String optimized = optimizeForVoice(raw.toString());
            if (onToken != null && !optimized.isBlank()) {
                onToken.accept(optimized);
            }

            log.info("LLM sentence stream response for session {}: {}", session.getId(),
                    optimized.substring(0, Math.min(100, optimized.length())));
            return optimized;
        } catch (Exception e) {
            log.error("LLM sentence stream error for session {}: {}", session.getId(), e.getMessage(), e);
            return mapLlmErrorToUserMessage(e);
        }
    }

    /**
     * 构建系统提示词与用户消息。
     *
     * <h3>安全设计</h3>
     * <ul>
     *   <li>历史对话逐条 {@code sanitize}——防御候选人通过语音输入夹带提示注入</li>
     *   <li>当前用户输入用分隔符包裹（wrapWithDelimiters）：既是防注入，
     *       也让 LLM 明确「用户说了什么」的边界，历史与当前对话用
     *       【之前的对话】/【当前对话】标记区隔</li>
     * </ul>
     */
    private PromptContext buildPromptContext(String userInput, VoiceInterviewSessionEntity session, List<String> conversationHistory) {
        String resumeText = null;
        if (session.getResumeId() != null) {
            ResumeEntity resume = resumeRepository.findById(session.getResumeId()).orElse(null);
            if (resume != null) {
                resumeText = resume.getResumeText();
            }
        }

        String systemPrompt = promptService.generateSystemPromptWithContext(session.getSkillId(), resumeText);

        StringBuilder promptBuilder = new StringBuilder();
        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            promptBuilder.append("【之前的对话】\n");
            for (String message : conversationHistory) {
                promptBuilder.append(promptSanitizer.sanitize(message)).append("\n");
            }
            promptBuilder.append("\n【当前对话】\n");
        }
        promptBuilder.append("用户：").append(
                promptSanitizer.wrapWithDelimiters("input", promptSanitizer.sanitize(userInput)));
        return new PromptContext(systemPrompt, promptBuilder.toString());
    }

    /**
     * 将 LLM 异常映射为对用户友好的中文提示。
     *
     * <p>按错误特征关键词分类（认证/超时/限流/网络），匹配不到时给通用兜底文案。
     * 语音场景下用户只能听到文案，因此不暴露任何内部细节（堆栈、原始异常类名）。
     */
    private String mapLlmErrorToUserMessage(Exception e) {
        String errorMessage = e.getMessage();
        if (errorMessage != null) {
            if (errorMessage.contains("403") || errorMessage.contains("ACCESS_DENIED") ||
                    errorMessage.contains("Authentication")) {
                return "AI 服务认证失败，请检查 API Key 配置";
            } else if (errorMessage.contains("timeout") || errorMessage.contains("Timeout")) {
                return "AI 服务响应超时，请稍后重试";
            } else if (errorMessage.contains("429") || errorMessage.contains("rate limit") ||
                    errorMessage.contains("quota")) {
                return "AI 服务调用频率超限，请稍后重试";
            } else if (errorMessage.contains("connection") || errorMessage.contains("network")) {
                return "AI 服务网络连接失败，请检查网络";
            }
        }
        return "抱歉，AI 服务暂时不可用，请稍后重试";
    }

    /**
     * 语音场景文本优化：剥离 Markdown 符号、压平空白、超长截断到句子边界。
     *
     * <h3>截断策略（宁可短、不可断句）</h3>
     * 超过 aiQuestionMaxChars 后从该位置向前找最后一个终止标点：
     * <ul>
     *   <li>标点位置 &gt;= 半长：截断到标点处（保留完整句子，丢弃半截尾巴）</li>
     *   <li>标点位置 &lt; 半长（前半段都没有句子结束）：截断后补省略号「…」，
     *       TTS 会把省略号读作停顿，暗示还有后续</li>
     * </ul>
     * 阈值 maxChars/2 的取舍：若全文只有一个终止标点在开头，按标点截断会丢
     * 掉几乎全部内容；此时添加省略号保留更多信息更合理。
     */
    private String optimizeForVoice(String content) {
        String normalized = normalizeRealtimeText(content);
        if (normalized.isBlank()) {
            return "请继续。";
        }

        int maxChars = Math.max(80, voiceInterviewProperties.getAiQuestionMaxChars());
        if (normalized.length() <= maxChars) {
            return normalized;
        }

        String truncated = normalized.substring(0, maxChars);
        int lastTerminal = -1;
        for (int i = truncated.length() - 1; i >= 0; i--) {
            if (TERMINAL_PUNCTUATION.indexOf(truncated.charAt(i)) >= 0) {
                lastTerminal = i;
                break;
            }
        }
        if (lastTerminal >= maxChars / 2) {
            return truncated.substring(0, lastTerminal + 1);
        }

        return truncated + "…";
    }

    /**
     * 实时文本归一化：剥掉 Markdown 语法（加粗/代码块/列表符号），
     * 把连续空白压成单个空格。
     *
     * <p>LLM 常输出带 Markdown 的文本；文本面试无所谓，但语音面试要把文本
     * 原样送去 TTS 读出来——「**答案**」会被读成「星号星号答案星号星号」。
     * 流式过程中也在不断归一化：确保前端字幕与 TTS 文本一致。
     */
    private String normalizeRealtimeText(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return content
                .replace("**", "")
                .replace("```", "")
                .replace("`", "")
                .replaceAll("(?m)^\\s*[-*+]\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * 判断一个流式 token 中是否含有句子终止标点。
     * 以 token 为单位粗筛（终止标点一般出现在句尾 token），
     * 真正切句时仍基于累计文本定位，粗筛只是避免每个 token 都做切分运算。
     */
    private boolean hasTerminalSince(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (TERMINAL_PUNCTUATION.indexOf(token.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private record PromptContext(String systemPrompt, String userPrompt) {}
}

