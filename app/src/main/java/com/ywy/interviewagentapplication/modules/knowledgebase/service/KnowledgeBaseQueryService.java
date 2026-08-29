package com.ywy.interviewagentapplication.modules.knowledgebase.service;

import com.ywy.interviewagentapplication.common.ai.LlmProviderRegistry;
import com.ywy.interviewagentapplication.common.ai.PromptSecurityConstants;
import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.QueryRequest;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.QueryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库查询服务：RAG 问答的完整管线。
 *
 * <h3>RAG 管线（检索增强生成）</h3>
 * <pre>
 * 用户问题
 *    │
 *    ▼
 * ┌──────────────┐
 * │ 1. 查询改写     多轮追问补全上下文（"它的缺点呢？" → "Redis RDB 的缺点是什么？"）
 * │ (Query Rewrite)  失败时降级为原问题继续
 * └──────┬───────┘
 *        ▼
 * ┌──────────────┐
 * │ 2. 向量检索     候选问题依次检索（改写结果优先，原问题兜底）
 * │ (Retrieval)     按问题长度动态调整 topK/minScore
 * └──────┬───────┘
 *        ▼
 * ┌──────────────┐
 * │ 3. 上下文拼接   相关文档片段拼接 + 提示词渲染
 * │ (Augment)
 * └──────┬───────┘
 *        ▼
 * ┌──────────────┐
 * │ 4. LLM 生成     同步（call）或流式（stream）
 * │ (Generate)
 * └──────────────┘
 * </pre>
 *
 * <h3>查询改写的价值</h3>
 * 多轮对话中的追问通常是"省略式"的："那缺点呢？"、"第二种呢？"。
 * 直接向量化这类问题，Embedding 向量与知识库内容的相似度极低，
 * 检索结果必然糟糕。改写让 LLM 结合历史补全问题，使检索环节拿到完整语义的问题。
 * <p>
 * 改写的降级策略：LLM 调用失败（超时/不可用）时使用原问题继续，
 * 宁可检索质量差一点，也不能让整个问答链路因改写失败而中断。
 *
 * <h3>动态检索参数</h3>
 * 短问题放宽（topK=20, minScore=0.25）、长问题收紧（topK=8, minScore=0.28）。详见 {@link KnowledgeBaseQueryProperties} 的类注释。
 *
 * <h3>流式输出的"探测窗口"模式</h3>
 * {@link #normalizeStreamOutput} 是流式输出的关键优化：
 * 先缓冲前 120 字符观察。如果命中"无信息"模板（如"没有找到相关信息"），
 * 立即中断 LLM 流并用固定模板替换（避免长篇拒答浪费 token 和时间）；
 * 未命中则透传缓冲并切换到实时透传模式。
 *
 * @see KnowledgeBaseVectorService 向量检索
 * @see KnowledgeBaseQueryProperties 管线参数配置
 */
@Slf4j
@Service
public class KnowledgeBaseQueryService {
    /** 检索无结果时的固定回复 */
    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";
    /** 流式探测窗口的字符数——缓冲前 120 字符判断是否为"无信息"回答 */
    private static final int STREAM_PROBE_CHARS = 120;
    /** 查询改写时助手消息的截断长度——防止 rewrite prompt 过长 */
    private static final int MAX_REWRITE_HISTORY_CHAR = 200;

    private final LlmProviderRegistry llmProviderRegistry;
    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseCountService countService;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final PromptTemplate rewritePromptTemplate;
    private final boolean rewriteEnabled;
    private final int shortQueryLength;
    private final int topkShort;
    private final int topkMedium;
    private final int topkLong;
    private final double minScoreShort;
    private final double minScoreDefault;

    public KnowledgeBaseQueryService(
            LlmProviderRegistry llmProviderRegistry,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseListService listService,
            KnowledgeBaseCountService countService,
            KnowledgeBaseQueryProperties queryProperties,
            ResourceLoader resourceLoader) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.vectorService = vectorService;
        this.listService = listService;
        this.countService = countService;
        this.systemPromptTemplate = new PromptTemplate(
                resourceLoader.getResource(queryProperties.getSystemPromptPath())
                        .getContentAsString(StandardCharsets.UTF_8)
        );
        this.userPromptTemplate = new PromptTemplate(
                resourceLoader.getResource(queryProperties.getUserPromptPath())
                        .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewritePromptTemplate = new PromptTemplate(
                resourceLoader.getResource(queryProperties.getRewritePromptPath())
                        .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewriteEnabled = queryProperties.getRewrite().isEnabled();
        this.shortQueryLength = queryProperties.getSearch().getShortQueryLength();
        this.topkShort = queryProperties.getSearch().getTopkShort();
        this.topkMedium = queryProperties.getSearch().getTopkMedium();
        this.topkLong = queryProperties.getSearch().getTopkLong();
        this.minScoreShort = queryProperties.getSearch().getMinScoreShort();
        this.minScoreDefault = queryProperties.getSearch().getMinScoreDefault();
    }

    /** 获取默认 LLM 客户端 */
    private ChatClient getChatClient() {
        return llmProviderRegistry.getDefaultChatClient();
    }

    /**
     * 基于单个知识库回答用户问题（同步）
     *
     * @param knowledgeBaseId 知识库ID
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(Long knowledgeBaseId, String question) {
        return answerQuestion(List.of(knowledgeBaseId), question);
    }

    /**
     * 基于多个知识库回答用户问题（同步）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(List<Long> knowledgeBaseIds, String question) {
        log.info("收到知识库提问: kbIds={}, question={}", knowledgeBaseIds, question);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return NO_RESULT_RESPONSE;
        }

        countService.updateQuestionCounts(knowledgeBaseIds);

        QueryContext queryContext = buildQueryContext(question, List.of());
        List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);

        if (!hasEffectiveHit(relevantDocs)) {
            return NO_RESULT_RESPONSE;
        }

        String context = relevantDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(context, question);

        try {
            String answer = getChatClient().prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            answer = normalizeAnswer(answer);

            log.info("知识库问答完成: kbIds={}", knowledgeBaseIds);
            return answer;

        } catch (Exception e) {
            log.error("知识库问答失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "知识库查询失败：" + e.getMessage());
        }
    }

    /**
     * 构建系统提示词：基础模板 + 反注入指令。
     * <p>
     * ANTI_INJECTION_INSTRUCTION 提示 LLM 将检索上下文视为"数据"而非"指令"。
     * 防止用户容注入恶意内容。
     */
    private String buildSystemPrompt() {
        return systemPromptTemplate.render()
                + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
    }

    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(String context, String question) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("context", context);
        variables.put("question", question);
        return userPromptTemplate.render(variables);
    }

    /**
     * 查询知识库并返回完整响应（含知识库来源信息）。
     * <p>
     * 多知识库的名称用"、"连接展示；主标识取第一个知识库 ID
     */
    public QueryResponse queryKnowledgeBase(QueryRequest request) {
        String answer = answerQuestion(request.knowledgeBaseIds(), request.question());

        // 获取知识库名称（多个知识库用逗号分隔）
        List<String> kbNames = listService.getKnowledgeBaseNames(request.knowledgeBaseIds());
        String kbNamesStr = String.join("、", kbNames);

        // 使用第一个知识库ID作为主要标识（兼容前端）
        Long primaryKbId = request.knowledgeBaseIds().getFirst();

        return new QueryResponse(answer, primaryKbId, kbNamesStr);
    }

    /**
     * 流式查询知识库（SSE，无上下文消息，即无历史对话）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
        return answerQuestionStream(knowledgeBaseIds, question, List.of());
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文）。
     *
     * <h4>核心特点</h4>
     * <ol>
     *   <li>携带历史消息——多轮对话的上下文延续</li>
     *   <li>查询改写使用历史——追问补全更精准</li>
     *   <li>输出带探测窗口——快速终止"无信息"拒答（节省 token）</li>
     *   <li>错误返回错误流而非抛异常——SSE 连接已建立，异常信息应通过流传递</li>
     * </ol>
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param question         用户问题
     * @param history          历史对话消息（可为空或 null，即没有历史对话消息）
     * @return 流式响应（Flux&lt;String&gt;，每块一段文本）
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question, List<Message> history) {
        log.info("收到知识库流式提问: kbIds={}, question={}, historySize={}", knowledgeBaseIds, question,
                history != null ? history.size() : 0);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return Flux.just(NO_RESULT_RESPONSE);
        }

        try {
            // 1. 验证知识库是否存在并更新问题计数
            countService.updateQuestionCounts(knowledgeBaseIds);

            // 2. Query rewrite + 动态参数检索
            List<Message> effectiveHistory = sanitizeHistory(history);
            QueryContext queryContext = buildQueryContext(question, effectiveHistory);
            List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);

            if (!hasEffectiveHit(relevantDocs)) {
                return Flux.just(NO_RESULT_RESPONSE);
            }

            // 3. 构建上下文
            String context = relevantDocs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));

            log.debug("检索到 {} 个相关文档片段", relevantDocs.size());

            // 4. 构建提示词
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(context, question);

            // 5. 流式调用（带历史上下文）+ 探测窗口归一化
            var promptSpec = getChatClient().prompt().system(systemPrompt);
            if (!effectiveHistory.isEmpty()) {
                promptSpec = promptSpec.messages(effectiveHistory);
            }
            Flux<String> responseFlux = promptSpec
                    .user(userPrompt)
                    .stream()
                    .content();

            log.info("开始流式输出知识库回答(探测窗口): kbIds={}", knowledgeBaseIds);
            return normalizeStreamOutput(responseFlux)
                    .doOnComplete(() -> log.info("流式输出完成: kbIds={}", knowledgeBaseIds))
                    .onErrorResume(e -> {
                        log.error("流式输出失败: kbIds={}, error={}", knowledgeBaseIds, e.getMessage(), e);
                        return Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。");
                    });

        } catch (Exception e) {
            log.error("知识库流式问答失败: {}", e.getMessage(), e);
            return Flux.just("【错误】知识库查询失败：" + e.getMessage());
        }
    }

    /**
     * 构建查询上下文：问题归一化 + 查询改写 + 检索参数解析。
     * <p>
     * 由于候选问题列表使用 {@link LinkedHashSet} 去重，且改写结果（改写失败会返回原问题）先加入集合，原问题随后（兜底），
     * 因此在改写失败时保证候选列表只存在一个原始问题，改写成功时存在两个候选查询：改写 + 原始。
     */
    private QueryContext buildQueryContext(String originalQuestion, List<Message> history) {
        String normalizedQuestion = normalizeQuestion(originalQuestion);
        String rewrittenQuestion = rewriteQuestion(normalizedQuestion, history);
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(rewrittenQuestion);
        candidates.add(normalizedQuestion);

        SearchParams searchParams = resolveSearchParams(normalizedQuestion);
        return new QueryContext(normalizedQuestion, new ArrayList<>(candidates), searchParams);
    }

    /** 历史消息净化。当前实现直接透传（保留扩展点） */
    private List<Message> sanitizeHistory(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history;
    }

    /** 问题归一化：去首尾空白，null 转空字符串 */
    private String normalizeQuestion(String question) {
        return question == null ? "" : question.trim();
    }

    /**
     * 向量检索：按候选查询地顺序依次尝试，第一个命中有效结果的问题胜出。若没有任何有效结果，返回空列表
     * <p>
     * 检索策略："改写问题优先，原问题兜底"，第一个返回有效结果的候选查询胜出。
     * 不需要合并多个候选的结果（改写结果与原问题的检索结果高度重叠，合并反而引入排序噪音）。
     */
    private List<Document> retrieveRelevantDocs(QueryContext queryContext, List<Long> knowledgeBaseIds) {
        for (String candidateQuery : queryContext.candidateQueries()) {
            if (candidateQuery.isBlank()) {
                continue;
            }
            List<Document> docs = vectorService.similaritySearch(
                    candidateQuery,
                    knowledgeBaseIds,
                    queryContext.searchParams().topK(),
                    queryContext.searchParams().minScore()
            );
            log.info("检索候选 query='{}'，命中 {} 条", candidateQuery, docs.size());
            if (hasEffectiveHit(docs)) {
                return docs;
            }
        }
        return List.of();
    }

    /**
     * 按问题长度调整检索参数（三档策略）。
     * <p>
     * 长度按"去除空白后的紧凑字符数"计算：空白字符不携带语义信息，不应影响长度判断。
     * 三档：≤shortQueryLength（短）、≤12（中）、>12（长）。
     */
    private SearchParams resolveSearchParams(String question) {
        int compactLength = question.replaceAll("\\s+", "").length();
        if (compactLength <= shortQueryLength) {
            return new SearchParams(topkShort, minScoreShort);
        }
        if (compactLength <= 12) {
            return new SearchParams(topkMedium, minScoreDefault);
        }
        return new SearchParams(topkLong, minScoreDefault);
    }

    /**
     * 查询改写：调用 LLM 将用户问题补全。
     * <p>
     * <b>降级策略</b>：改写失败（LLM 不可用/超时/返回空）时返回原问题。改写是增强而非必需，不能因改写失败中断整个问答链路。
     * <p>
     * 历史消息以"用户: ... / 助手: ..."的文本格式注入 rewrite prompt，让 LLM 理解对话脉络后完成指代消解和省略补全。
     */
    private String rewriteQuestion(String question, List<Message> history) {
        if (!rewriteEnabled || question.isBlank()) {
            return question;
        }
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("question", question);
            variables.put("history", formatHistoryForRewrite(history));
            String rewritePrompt = rewritePromptTemplate.render(variables);
            String rewritten = getChatClient().prompt()
                    .user(rewritePrompt)
                    .call()
                    .content();
            if (rewritten == null || rewritten.isBlank()) {
                return question;
            }
            String normalized = rewritten.trim();
            log.info("Query rewrite: origin='{}', rewritten='{}', historySize={}", question, normalized, history.size());
            return normalized;
        } catch (Exception e) {
            log.warn("Query rewrite 失败，使用原问题继续检索: {}", e.getMessage());
            return question;
        }
    }

    /**
     * 将历史消息格式化为文本摘要，用于查询改写提示词。
     * <p>
     * 格式：
     * <pre>
     * 用户: Redis 的持久化方式有哪些？
     * 助手: RDB 和 AOF 两种，RDB 是快照方式...
     * </pre>
     * 助手消息截断到 {@value #MAX_REWRITE_HISTORY_CHAR} 字符，因为改写只需要理解话题脉络，不需要完整回答内容。
     * 截断防止 rewrite prompt 过长，导致 LLM 注意力分散和 token 浪费。
     */
    private String formatHistoryForRewrite(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message msg : history) {
            if (msg instanceof UserMessage) {
                sb.append("用户: ").append(msg.getText()).append("\n");
            } else if (msg instanceof AssistantMessage) {
                // 截断过长的助手回复，避免 rewrite prompt 过长
                String text = msg.getText();
                if (text.length() > MAX_REWRITE_HISTORY_CHAR) {
                    text = text.substring(0, MAX_REWRITE_HISTORY_CHAR) + "...";
                }
                sb.append("助手: ").append(text).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /** 检索结果是否有有效命中。结果不为空或 null 即为有效，返回 true。 */
    private boolean hasEffectiveHit(List<Document> docs) {
        return docs != null && !docs.isEmpty();
    }

    /**
     * 回答归一化：空回答和"无信息"模板回答统一替换为固定提示。其他回答则仅消除字符串首尾的空格。
     * <p>
     * 为什么要识别"无信息"模板？LLM 在没有足够信息时会生成各种
     * 拒答话术（"没有找到相关信息"、"信息不足"等），这些话术措辞不一，前端难以统一处理。
     * 归一化为固定提示后，前端可以精确匹配并展示统一的"未找到"UI。
     */
    private String normalizeAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return NO_RESULT_RESPONSE;
        }
        String normalized = answer.trim();
        if (isNoResultLike(normalized)) {
            return NO_RESULT_RESPONSE;
        }
        return normalized;
    }

    /**
     * 通过关键字包含匹配，判断文本是否属于"无信息"类拒答话术。
     * <p>
     * LLM 的拒答话术虽然多样，但通常包含这些关键短语。该方法可根据实际观察到的
     * LLM 输出不断扩充拒答表述。
     */
    private boolean isNoResultLike(String text) {
        return text.contains("没有找到相关信息")
                || text.contains("未检索到相关信息")
                || text.contains("信息不足")
                || text.contains("超出知识库范围")
                || text.contains("无法根据提供内容回答");
    }

    /**
     * 流式输出归一化。
     *
     * <h4>工作原理</h4>
     * <pre>
     * ┌───────────── 缓冲阶段（前 120 字符）─────────────┐
     * │ 持续累积 chunk 并检测"无信息"模板
     * │  ┌─ 命中"无信息" → 输出固定模板 + 终止（省 token）
     * │  └─ 达到 120 字符 → 释放缓冲，切换到透传
     * └───────────────────────────────────────────────┘
     * ┌───────────── 透传阶段 ──────────────────────────┐
     * │ 实时透传每个 chunk（零延迟）
     * └────────────────────────────────────────────────┘
     * </pre>
     *
     * <h4>为什么需要这个模式？</h4>
     * LLM 对"无信息"类问题（检索失败但没拦截住）会生成长篇拒答，
     * 先解释为什么没找到、再建议用户怎么做，浪费数百 token 和数秒时间。
     * 探测窗口在前 120 字符内识别拒答并立即终止，用户几乎无感知地收到简洁的固定提示。
     * <p>
     * 正常回答的代价：前 120 字符有轻微缓冲延迟（累积到 120 字符才释放），但与节省的拒答开销相比完全值得。
     *
     * <h4>实现细节</h4>
     * 使用 Flux.create 手动管理流：订阅原始流、维护探测状态、处理取消。
     *
     * @param rawFlux LLM 的原始流式输出
     * @return 归一化后的流式输出
     */
    private Flux<String> normalizeStreamOutput(Flux<String> rawFlux) {
        return Flux.create(sink -> {
            StringBuilder probeBuffer = new StringBuilder();
            // 是否已切换到透传模式
            AtomicBoolean passthrough = new AtomicBoolean(false);
            // 是否已终止（防重复完成）
            AtomicBoolean completed = new AtomicBoolean(false);
            // 保存上游订阅引用，方便随时取消
            final Disposable[] disposableRef = new Disposable[1];

            disposableRef[0] = rawFlux.subscribe(
                    chunk -> {
                        if (completed.get() || sink.isCancelled()) {
                            return;
                        }
                        if (passthrough.get()) {
                            // 透传模式：直接转发
                            sink.next(chunk);
                            return;
                        }

                        probeBuffer.append(chunk);
                        String probeText = probeBuffer.toString();
                        // 命中"无信息"：输出固定模板并终止（丢弃 LLM 后续输出）
                        if (isNoResultLike(probeText)) {
                            completed.set(true);
                            sink.next(NO_RESULT_RESPONSE);
                            sink.complete();
                            if (disposableRef[0] != null) {
                                disposableRef[0].dispose();
                            }
                            return;
                        }

                        // 探测窗口结束：释放缓冲并切换透传
                        if (probeBuffer.length() >= STREAM_PROBE_CHARS) {
                            passthrough.set(true);
                            sink.next(probeText);
                            probeBuffer.setLength(0);
                        }
                    },
                    sink::error,
                    () -> {
                        // 流结束：如果还在缓冲阶段（回答不足 120 字符），归一化后输出
                        if (completed.get() || sink.isCancelled()) {
                            return;
                        }
                        if (!passthrough.get()) {
                            sink.next(normalizeAnswer(probeBuffer.toString()));
                        }
                        sink.complete();
                    }
            );

            sink.onCancel(() -> {
                if (disposableRef[0] != null) {
                    disposableRef[0].dispose();
                }
            });
        });
    }

    /** 检索参数 */
    private record SearchParams(int topK, double minScore) {
    }

    /** 查询上下文：归一化问题 + 候选查询列表 + 检索参数 */
    private record QueryContext(String originalQuestion, List<String> candidateQueries, SearchParams searchParams) {
    }
}

