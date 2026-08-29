package com.ywy.interviewagentapplication.modules.knowledgebase.service;

import com.ywy.interviewagentapplication.common.ai.LlmProviderRegistry;
import com.ywy.interviewagentapplication.common.ai.PromptSanitizer;
import com.ywy.interviewagentapplication.common.ai.PromptSecurityConstants;
import com.ywy.interviewagentapplication.common.ai.StructuredOutputInvoker;
import com.ywy.interviewagentapplication.common.constant.CommonConstants.InterviewDefaults;
import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseQuestionEntity;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseQuestionFollowUpDTO;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseQuestionStatus;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.QuestionGenerationConfig;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.KnowledgeBaseRepository;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository.CategoryCount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库题目异步生成服务。
 *
 * <h3>事务边界设计（本服务最重要的架构决策）</h3>
 * 生成流程中只有"替换题目 + 更新状态"在事务中执行，
 * LLM 调用和上下文检索<b>不在事务内</b>：
 * <ul>
 *   <li>检索和 LLM 调用失败不需要回滚数据库——没有写入任何数据</li>
 *   <li>收尾事务（replaceQuestionsAndComplete）小而快——只做删旧题+存新题+改状态</li>
 * </ul>
 * 这是"长任务异步处理"的标准模式：<b>重计算在事务外，持久化在最小事务中</b>。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseQuestionGenerationService {
    /** 检索上下文的目标文档数（四组查询的合并上限） */
    private static final int RETRIEVAL_TOP_K = 12;
    /** 每组查询的检索命中数 */
    private static final int RETRIEVAL_QUERY_TOP_K = 4;
    /** 上下文总长度上限（控制 LLM 输入的 token 量） */
    private static final int MAX_CONTEXT_CHARS = 5000;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseQuestionRepository questionRepository;
    private final KnowledgeBaseVectorService vectorService;
    private final LlmProviderRegistry llmProviderRegistry;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final PromptSanitizer promptSanitizer;
    private final QuestionGenerationStateService stateService;
    private final ObjectMapper objectMapper;

    @Value("classpath:prompts/knowledgebase-question-generation-system.st")
    private Resource systemPromptResource;

    @Value("classpath:prompts/knowledgebase-question-generation-user.st")
    private Resource userPromptResource;

    private final BeanOutputConverter<QuestionListDTO> outputConverter =
            new BeanOutputConverter<>(QuestionListDTO.class);

    /** LLM 返回的题目列表包装 */
    public record QuestionListDTO(List<QuestionDTO> questions) {}

    /** LLM 返回的单道题（含追问） */
    public record QuestionDTO(
            String category,
            String type,
            String question,
            String topicSummary,
            String referenceAnswer,
            List<String> keyPoints,
            String scoringRubric,
            List<KnowledgeBaseQuestionFollowUpDTO> followUps
    ) {}

    /** 构建结果：有效题目实体列表 + 跳过的重复题数 */
    private record GenerationBatch(
            List<KnowledgeBaseQuestionEntity> questions,
            int skippedCount
    ) {
    }

    /**
     * 指定知识库，执行批量问题生成（由 Consumer 调用，不在事务中）。
     * <p>
     * 流程：校验任务有效性 → 检索上下文 → 调用 LLM → 构建实体 →
     * 替换旧题并完成（小事务，在 StateService 中）。
     * <p>
     * 任务有效性确认：消息携带的 taskId 与数据库当前 taskId 对比。防止过期消息（排队期间，用户重新提交了新任务）执行生成。
     *
     * @param kbId   知识库 ID
     * @param taskId 任务 ID（幂等标识）
     * @param config 生成参数快照
     */
    public void executeGeneration(
            Long kbId,
            String taskId,
            QuestionGenerationConfig config
    ) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));

        // 再次确认任务ID匹配
        if (!taskId.equals(kb.getQuestionGenTaskId())) {
            log.info("任务ID不匹配，放弃生成: kbId={}, msgTaskId={}, currentTaskId={}",
                    kbId, taskId, kb.getQuestionGenTaskId());
            return;
        }

        String normalizedDifficulty = normalizeDifficulty(config.difficulty());
        int normalizedFollowUp = Math.max(0, Math.min(config.followUpCount(), 5));
        int normalizedCategoryLimit = Math.max(1, Math.min(config.categoryLimit(), 5));

        // 1. 检索上下文（不在事务中）
        String context = buildGenerationContext(kb);

        // 2. 调用 LLM（不在事务中）
        ChatClient chatClient = llmProviderRegistry.getPlainChatClient(config.llmProvider());
        QuestionListDTO generated = callLlm(kb, chatClient, normalizedDifficulty,
                Math.max(1, config.questionCount()), normalizedFollowUp, normalizedCategoryLimit, context);

        // 3. 校验生成结果
        if (generated == null || generated.questions() == null || generated.questions().isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED, "知识库题库生成结果为空");
        }

        // 4. 构建实体列表（不在事务中）
        GenerationBatch batch =
                buildEntities(kb, normalizedDifficulty, context, normalizedFollowUp, generated);
        if (batch.questions().isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED, "知识库题库生成结果无有效题干");
        }

        // 5. 在一个小事务中校验当前任务、替换题目并更新完成状态
        boolean completed = stateService.replaceQuestionsAndComplete(
                kbId, taskId, batch.questions(), batch.skippedCount());
        if (!completed) {
            log.info("题目生成任务已被替换，丢弃旧结果: kbId={}, taskId={}", kbId, taskId);
            return;
        }

        log.info("知识库问题异步生成完成: kbId={}, taskId={}, count={}",
                kbId, taskId, batch.questions().size());
    }

    /**
     * 构造问题生成的提示词并调用 LLM。
     * <p>
     * 用户提示词包含：
     * <ul>
     *   <li>知识库名称</li>
     *   <li>生成参数（难度/数量/追问数/方向数）</li>
     *   <li>已有方向列表（引导 LLM 与现有方向保持一致性）</li>
     *   <li>已有题目列表（避免生成重复题目）</li>
     *   <li>检索上下文</li>
     * </ul>
     * 所有外部数据（知识库名、上下文）都经过 PromptSanitizer 清理
     * 并用分隔符包裹，防止提示词注入。
     */
    private QuestionListDTO callLlm(
            KnowledgeBaseEntity kb,
            ChatClient chatClient,
            String difficulty,
            int questionCount,
            int followUpCount,
            int categoryLimit,
            String context
    ) {
        try {
            String systemPrompt = loadTemplate(systemPromptResource).render()
                    + "\n\n"
                    + outputConverter.getFormat();
            String userPrompt = loadTemplate(userPromptResource)
                    .render(Map.of(
                            "knowledgeBaseName", promptSanitizer.sanitize(kb.getName()),
                            "difficulty", difficulty,
                            "questionCount", questionCount,
                            "followUpCount", followUpCount,
                            "categoryLimit", categoryLimit,
                            "existingCategories", promptSanitizer.sanitize(
                                    buildExistingCategorySection(kb.getId())),
                            "existingQuestions", promptSanitizer.sanitize(
                                    buildExistingQuestionSection(kb.getId(), difficulty)),
                            "context", PromptSecurityConstants.DATA_BOUNDARY_INSTRUCTION + "\n"
                                    + promptSanitizer.wrapWithDelimiters("knowledge-base",
                                    promptSanitizer.sanitize(context))
                    ));

            return structuredOutputInvoker.invoke(
                    chatClient,
                    systemPrompt,
                    userPrompt,
                    outputConverter,
                    ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                    "知识库题库生成失败：",
                    "知识库题库生成",
                    log
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("知识库题库生成LLM调用失败: kbId={}, error={}", kb.getId(), e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_GENERATION_FAILED,
                    "知识库题库生成失败：" + e.getMessage());
        }
    }

    /**
     * 将 LLM 返回的问题 DTO 列表构建为实体列表（含批次内去重）。
     * <p>
     * 跳过条件：null 题目、空白题干、批次内重复（归一化后相同则重复）。
     * <p>
     * 归一化策略（normalizeQuestionKey）：Unicode NFC 规范化 + 小写 + 去除非字母数字字符（汉字在 Unicode 中属于 Letter 类别）。
     * <p>
     * 生成的题目状态全部为 DRAFT，人工审核后启用。
     */
    private GenerationBatch buildEntities(
            KnowledgeBaseEntity kb,
            String difficulty,
            String sourceContext,
            int followUpCount,
            QuestionListDTO generated
    ) {
        List<KnowledgeBaseQuestionEntity> entities = new ArrayList<>();
        Set<String> batchKeys = new LinkedHashSet<>();
        int skippedCount = 0;
        for (QuestionDTO dto : generated.questions()) {
            if (dto == null || dto.question() == null || dto.question().isBlank()) {
                skippedCount += 1;
                continue;
            }
            String rawQuestion = dto.question().trim();
            String key = normalizeQuestionKey(rawQuestion);
            if (!batchKeys.add(key)) {
                skippedCount += 1;
                continue;
            }
            String category = normalizeCategory(dto.category(), kb.getName());
            KnowledgeBaseQuestionEntity entity = new KnowledgeBaseQuestionEntity();
            entity.setKnowledgeBase(kb);
            entity.setSkillId(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID);
            entity.setDifficulty(difficulty);
            entity.setType(trimToNull(dto.type()));
            entity.setCategory(category);
            entity.setQuestion(rawQuestion);
            entity.setTopicSummary(trimToNull(dto.topicSummary()));
            entity.setReferenceAnswer(trimToNull(dto.referenceAnswer()));
            entity.setKeyPointsJson(writeStringList(dto.keyPoints()));
            entity.setScoringRubric(trimToNull(dto.scoringRubric()));
            entity.setFollowUpsJson(writeFollowUps(dto.followUps(), followUpCount));
            entity.setSourceContext(sourceContext);
            entity.setKbContentHash(kb.getFileHash());
            entity.setStatus(KnowledgeBaseQuestionStatus.DRAFT);
            entities.add(entity);
        }
        return new GenerationBatch(entities, skippedCount);
    }

    /**
     * 构建知识库问题生成的检索上下文：四组查询的向量检索 + 去重 + 长度限制。
     * <p>
     * 依次执行四组知识抽取查询，每组取 top4，按文本去重后合并，最多 12 个片段；总长超过 5000 字符截断。
     * <p>
     * 检索阈值为 0（minScore=0）：生成场景需要 "尽可能多" 的素材，
     * 与问答场景的精确检索不同，素材宁多勿少（LLM 会自行过滤）。
     * 但这不是把整个知识库文档塞给 LLM，而是用四组 "知识抽取查询" 从向量库检索最有价值的内容片段：
     * <ul>
     *   <li>"核心概念 定义 背景 原理" → 概念类内容</li>
     *   <li>"关键流程 步骤 方法 工作机制" → 流程类内容</li>
     *   <li>"规则约束 条件 边界 例外 限制" → 规则类内容</li>
     *   <li>"典型案例 常见问题 应用场景 最佳实践" → 案例类内容</li>
     * </ul>
     * 四组查询覆盖了面试题目的主要素材类型，比 "随机检索 topK" 更能保证素材的多样性。
     */
    private String buildGenerationContext(KnowledgeBaseEntity kb) {
        List<Document> docs = new ArrayList<>();
        Set<String> seenTexts = new LinkedHashSet<>();
        for (String query : buildGenerationQueries()) {
            List<Document> hits = vectorService.similaritySearch(
                    query, List.of(kb.getId()), RETRIEVAL_QUERY_TOP_K, 0);
            for (Document doc : hits) {
                String text = doc.getText();
                if (text == null || text.isBlank() || !seenTexts.add(text.trim())) {
                    continue;
                }
                docs.add(doc);
                if (docs.size() >= RETRIEVAL_TOP_K) {
                    break;
                }
            }
            if (docs.size() >= RETRIEVAL_TOP_K) {
                break;
            }
        }
        if (docs.isEmpty()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED,
                    "知识库未检索到可用于生成题目的内容");
        }
        String context = docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));
        return context.length() <= MAX_CONTEXT_CHARS
                ? context
                : context.substring(0, MAX_CONTEXT_CHARS) + "\n...(知识库片段过长，已截断)";
    }

    /**
     * 四组知识抽取查询：覆盖面试题目的主要素材类型。
     * 每组查询是一串关键词（向量检索的查询不需要完整句子，关键词序列反而能命中更多相关片段）。
     */
    private List<String> buildGenerationQueries() {
        return List.of(
                "核心概念 定义 背景 原理",
                "关键流程 步骤 方法 工作机制",
                "规则约束 条件 边界 例外 限制",
                "典型案例 常见问题 应用场景 最佳实践"
        );
    }

    /** 已有方向摘要（最多 10 个，提示 LLM 保持方向一致性） */
    private String buildExistingCategorySection(Long knowledgeBaseId) {
        List<CategoryCount> categories = questionRepository.findCategoryCounts(knowledgeBaseId);
        if (categories.isEmpty()) {
            return "暂无已有方向";
        }
        return categories.stream()
                .limit(10)
                .map(c -> "- " + c.getCategory() + "（" + c.getCount() + " 题）")
                .collect(Collectors.joining("\n"));
    }

    /** 已有题目列表（最多 20 道同难度，尽量避免生成重复题目） */
    private String buildExistingQuestionSection(Long knowledgeBaseId, String difficulty) {
        List<String> questions = questionRepository
                .findTop20ByKnowledgeBase_IdAndDifficultyOrderByUpdatedAtDesc(knowledgeBaseId, difficulty)
                .stream()
                .map(KnowledgeBaseQuestionEntity::getQuestion)
                .filter(question -> question != null && !question.isBlank())
                .map(question -> "- " + question.trim())
                .toList();
        return questions.isEmpty() ? "暂无已有题目" : String.join("\n", questions);
    }

    private PromptTemplate loadTemplate(Resource resource) throws IOException {
        try (var input = resource.getInputStream()) {
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return new PromptTemplate(content);
        }
    }

    private String normalizeDifficulty(String difficulty) {
        if (difficulty == null || difficulty.isBlank()) {
            return InterviewDefaults.DIFFICULTY;
        }
        return difficulty.trim();
    }

    private String normalizeCategory(String category, String fallback) {
        if (category == null || category.isBlank()) {
            return fallback != null && !fallback.isBlank() ? fallback.trim() : "未分类";
        }
        return category.trim();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 问题归一化：
     * Unicode NFC 规范化（统一等价字符）+ 小写 + 去除非字母数字。
     * 归一化后"Redis 的持久化方式？"与"redis的持久化方式"相同。
     */
    private String normalizeQuestionKey(String question) {
        if (question == null) {
            return "";
        }
        String normalized = Normalizer.normalize(question, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i += 1) {
            char ch = normalized.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** List → JSON（null/空白过滤 + trim），序列化失败抛异常（数据完整性问题） */
    private String writeStringList(List<String> values) {
        try {
            List<String> sanitized = values == null ? List.of() : values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .toList();
            return objectMapper.writeValueAsString(sanitized);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化题目列表字段失败", e);
        }
    }

    /** 追问列表 → JSON（净化 + 截断到 followUpCount） */
    private String writeFollowUps(
            List<KnowledgeBaseQuestionFollowUpDTO> values,
            int followUpCount
    ) {
        try {
            List<KnowledgeBaseQuestionFollowUpDTO> sanitized = values == null ? List.of() : values.stream()
                    .filter(value -> value != null && value.question() != null && !value.question().isBlank())
                    .map(value -> new KnowledgeBaseQuestionFollowUpDTO(
                            value.question().trim(),
                            trimToNull(value.referenceAnswer()),
                            value.keyPoints() == null ? List.of() : value.keyPoints().stream()
                                    .filter(item -> item != null && !item.isBlank())
                                    .map(String::trim)
                                    .toList(),
                            trimToNull(value.scoringRubric())
                    ))
                    .limit(followUpCount)
                    .toList();
            return objectMapper.writeValueAsString(sanitized);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化追问字段失败", e);
        }
    }
}

