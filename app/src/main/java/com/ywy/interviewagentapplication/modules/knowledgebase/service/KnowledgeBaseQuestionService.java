package com.ywy.interviewagentapplication.modules.knowledgebase.service;

import com.ywy.interviewagentapplication.common.constant.CommonConstants.InterviewDefaults;
import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.modules.knowledgebase.listener.QuestionGenStreamProducer;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.CreateKnowledgeBaseQuestionRequest;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.GenerateKnowledgeBaseQuestionsRequest;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseQuestionDTO;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseQuestionEntity;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseQuestionFollowUpDTO;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseQuestionStatus;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.QuestionGenStatusResponse;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.QuestionGenerationConfig;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.UpdateKnowledgeBaseQuestionRequest;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository.CategoryCount;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;

/**
 * 知识库题目管理服务：题库的 CRUD 和异步题目生成任务提交。
 *
 * <h3>职责划分</h3>
 * 题目相关的服务拆分为三个（单一职责）：
 * <ul>
 *   <li><b>本服务</b>：题库 CRUD（列表/创建/更新/删除/状态切换）+ 生成任务提交</li>
 *   <li><b>KnowledgeBaseQuestionGenerationService</b>：LLM 生成的执行（消费者调用）</li>
 *   <li><b>QuestionGenerationStateService</b>：生成任务状态机（事务边界）</li>
 * </ul>
 * 本服务是"同步操作"的入口，生成服务是"异步执行"的实现，状态服务是两者之间的协调者。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseQuestionService {

    private static final int DEFAULT_FOLLOW_UP_COUNT = 2;
    private static final int DEFAULT_CATEGORY_LIMIT = 3;
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<KnowledgeBaseQuestionFollowUpDTO>> FOLLOW_UP_LIST_TYPE =
            new TypeReference<>() {
            };

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseQuestionRepository questionRepository;
    private final ObjectMapper objectMapper;
    private final QuestionGenStreamProducer questionGenStreamProducer;
    private final QuestionGenerationStateService questionGenerationStateService;

    /**
     * 列出知识库题目（支持状态/方向/难度/关键词筛选）。
     * <p>
     * 数据库只按状态过滤（两种查询路径），其余三维在内存过滤——
     * 关键词匹配覆盖题干、参考答案、评分规则、摘要、方向五个文本字段（不区分大小写）。
     */
    @Transactional(readOnly = true)
    public List<KnowledgeBaseQuestionDTO> listQuestions(Long knowledgeBaseId,
                                                        KnowledgeBaseQuestionStatus status,
                                                        String category,
                                                        String difficulty,
                                                        String keyword) {
        List<KnowledgeBaseQuestionEntity> questions = status == null
                ? questionRepository.findByKnowledgeBase_IdOrderByUpdatedAtDesc(knowledgeBaseId)
                : questionRepository.findByKnowledgeBase_IdAndStatusOrderByUpdatedAtDesc(knowledgeBaseId, status);
        String categoryFilter = trimToNull(category);
        String difficultyFilter = trimToNull(difficulty);
        String keywordFilter = trimToNull(keyword);
        return questions.stream()
                .filter(q -> categoryFilter == null || categoryFilter.equals(q.getCategory()))
                .filter(q -> difficultyFilter == null || difficultyFilter.equals(q.getDifficulty()))
                .filter(q -> keywordFilter == null || containsKeyword(q, keywordFilter))
                .map(this::toDTO)
                .toList();
    }

    /**
     * 列出知识库下出现过的方向（含题目计数，按频次降序）。
     * 用于前端下拉筛选和"开始面试"弹窗的方向选择。
     */
    @Transactional(readOnly = true)
    public List<CategoryCount> listCategories(Long knowledgeBaseId) {
        return questionRepository.findCategoryCounts(knowledgeBaseId);
    }

    /**
     * 手动创建题目。
     * <p>
     * kbContentHash 记录当前知识库内容哈希，用于后续检测题目过期。
     * 状态默认为 DRAFT（请求未指定时），因为手动创建的题目同样走审核流程。
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseQuestionDTO createQuestion(Long knowledgeBaseId,
                                                   CreateKnowledgeBaseQuestionRequest request) {
        KnowledgeBaseEntity kb = getKnowledgeBase(knowledgeBaseId);
        KnowledgeBaseQuestionEntity question = new KnowledgeBaseQuestionEntity();
        question.setKnowledgeBase(kb);
        applyCreateRequest(question, request);
        question.setKbContentHash(kb.getFileHash());
        question.setStatus(request.status() != null ? request.status() : KnowledgeBaseQuestionStatus.DRAFT);
        return toDTO(questionRepository.save(question));
    }

    /**
     * 更新题目（部分更新语义）。
     * <p>
     * 每个字段独立判断，null 表示"不修改"。
     * 特殊处理：category 和 question 在非 null 但 blank 时抛异常（这两个字段不允许清空，它们是题目的核心身份）。
     * 其他字段 blank 时通过 trimToNull 转为 null（允许清空）。
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseQuestionDTO updateQuestion(Long questionId, UpdateKnowledgeBaseQuestionRequest request) {
        KnowledgeBaseQuestionEntity question = getQuestion(questionId);
        if (request.difficulty() != null) {
            question.setDifficulty(normalizeDifficulty(request.difficulty()));
        }
        if (request.type() != null) {
            question.setType(trimToNull(request.type()));
        }
        if (request.category() != null) {
            if (request.category().isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "面试方向不能为空");
            }
            question.setCategory(request.category().trim());
        }
        if (request.question() != null) {
            if (request.question().isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "题干不能为空");
            }
            question.setQuestion(request.question().trim());
        }
        if (request.topicSummary() != null) {
            question.setTopicSummary(trimToNull(request.topicSummary()));
        }
        if (request.referenceAnswer() != null) {
            question.setReferenceAnswer(trimToNull(request.referenceAnswer()));
        }
        if (request.keyPoints() != null) {
            question.setKeyPointsJson(writeStringList(request.keyPoints()));
        }
        if (request.scoringRubric() != null) {
            question.setScoringRubric(trimToNull(request.scoringRubric()));
        }
        if (request.followUps() != null) {
            question.setFollowUpsJson(writeFollowUps(request.followUps()));
        }
        if (request.sourceContext() != null) {
            question.setSourceContext(trimToNull(request.sourceContext()));
        }
        if (request.status() != null) {
            question.setStatus(request.status());
        }
        return toDTO(questionRepository.save(question));
    }

    /**
     * 更新题目状态（审核操作）。
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseQuestionDTO updateStatus(Long questionId, KnowledgeBaseQuestionStatus status) {
        KnowledgeBaseQuestionEntity question = getQuestion(questionId);
        question.setStatus(status);
        return toDTO(questionRepository.save(question));
    }

    /** 删除指定的题目，题目不存在抛出异常 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteQuestion(Long questionId) {
        if (!questionRepository.existsById(questionId)) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND);
        }
        questionRepository.deleteById(questionId);
    }

    /**
     * 提交异步问题生成任务。
     * 校验知识库存在且状态允许，更新状态为 QUEUED，然后向 Redis Stream 投递任务。
     */
    public QuestionGenStatusResponse submitGenerationTask(
            Long knowledgeBaseId,
            GenerateKnowledgeBaseQuestionsRequest request) {
        String difficulty = normalizeDifficulty(request.difficulty());
        int followUpCount = request.followUpCount() == null
                ? DEFAULT_FOLLOW_UP_COUNT
                : Math.max(0, Math.min(request.followUpCount(), 5));
        int categoryLimit = request.categoryLimit() == null
                ? DEFAULT_CATEGORY_LIMIT
                : Math.max(1, Math.min(request.categoryLimit(), 5));
        QuestionGenerationConfig config = new QuestionGenerationConfig(
                difficulty,
                Math.max(1, request.questionCount()),
                followUpCount,
                categoryLimit,
                trimToNull(request.llmProvider())
        );
        QuestionGenStatusResponse response =
                questionGenerationStateService.createTask(knowledgeBaseId, config);
        boolean sent = questionGenStreamProducer.sendGenerateTask(
                knowledgeBaseId, response.questionGenTaskId());
        return sent ? response : questionGenerationStateService.getStatus(knowledgeBaseId);
    }

    /** 查询指定数据库的问题生成任务的状态（前端轮询） */
    @Transactional(readOnly = true)
    public QuestionGenStatusResponse getGenerationStatus(Long knowledgeBaseId) {
        return questionGenerationStateService.getStatus(knowledgeBaseId);
    }

    /** 将创建知识库问题请求的字段映射到到知识库题目实体（skillId 固定默认值） */
    private void applyCreateRequest(KnowledgeBaseQuestionEntity question,
                                    CreateKnowledgeBaseQuestionRequest request) {
        question.setSkillId(KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID);
        question.setDifficulty(normalizeDifficulty(request.difficulty()));
        question.setType(trimToNull(request.type()));
        question.setCategory(normalizeCategory(request.category(), null));
        question.setQuestion(request.question().trim());
        question.setTopicSummary(trimToNull(request.topicSummary()));
        question.setReferenceAnswer(trimToNull(request.referenceAnswer()));
        question.setKeyPointsJson(writeStringList(request.keyPoints()));
        question.setScoringRubric(trimToNull(request.scoringRubric()));
        question.setFollowUpsJson(writeFollowUps(request.followUps()));
        question.setSourceContext(trimToNull(request.sourceContext()));
    }

    /**
     * 实体 → DTO 转换。
     * <p>
     * knowledgeBaseId 的取值优先级：关联知识库实体中的 ID 字段 > 关联的知识库 ID（声明为 insertable=false/updatable=false，save 后不一定回填，只能用于兜底）。
     * <p>
     * JSON 字段（keyPointsJson/followUpsJson）反序列化为 List 结构。
     */
    private KnowledgeBaseQuestionDTO toDTO(KnowledgeBaseQuestionEntity question) {
        Long knowledgeBaseId = question.getKnowledgeBase() != null && question.getKnowledgeBase().getId() != null
                ? question.getKnowledgeBase().getId()
                : question.getKnowledgeBaseId();
        return new KnowledgeBaseQuestionDTO(
                question.getId(),
                knowledgeBaseId,
                question.getKnowledgeBase() != null ? question.getKnowledgeBase().getName() : null,
                question.getSkillId(),
                question.getDifficulty(),
                question.getType(),
                question.getCategory(),
                question.getQuestion(),
                question.getTopicSummary(),
                question.getReferenceAnswer(),
                readStringList(question.getKeyPointsJson()),
                question.getScoringRubric(),
                readFollowUps(question.getFollowUpsJson()),
                question.getSourceContext(),
                question.getStatus(),
                question.getCreatedAt(),
                question.getUpdatedAt()
        );
    }

    /** 获取指定的知识库实体 */
    private KnowledgeBaseEntity getKnowledgeBase(Long knowledgeBaseId) {
        return knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
    }

    /** 获取指定的知识库问题实体 */
    private KnowledgeBaseQuestionEntity getQuestion(Long questionId) {
        return questionRepository.findById(questionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND));
    }

    /** 关键词匹配，覆盖题干/参考答案/评分规则/摘要/方向五个字段（不区分大小写） */
    private boolean containsKeyword(KnowledgeBaseQuestionEntity question, String keyword) {
        String lower = keyword.toLowerCase(Locale.ROOT);
        return contains(question.getQuestion(), lower)
                || contains(question.getReferenceAnswer(), lower)
                || contains(question.getScoringRubric(), lower)
                || contains(question.getTopicSummary(), lower)
                || contains(question.getCategory(), lower);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    /** JSON 字符串 → List（解析失败则降级返回空列表） */
    private List<String> readStringList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, STRING_LIST_TYPE);
        } catch (JacksonException e) {
            log.warn("解析题目字符串列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    // 解析追问列表
    private List<KnowledgeBaseQuestionFollowUpDTO> readFollowUps(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, FOLLOW_UP_LIST_TYPE);
        } catch (JacksonException e) {
            log.warn("解析追问列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** List → JSON 字符串（null/空白过滤 + trim 净化） */
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

    // 将追问列表反序列化为字符串
    private String writeFollowUps(List<KnowledgeBaseQuestionFollowUpDTO> values) {
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
                    .toList();
            return objectMapper.writeValueAsString(sanitized);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化追问字段失败", e);
        }
    }

    /** 规范 difficulty 字段的格式 ，若为 null/空 则返回默认难度 */
    private String normalizeDifficulty(String difficulty) {
        if (difficulty == null || difficulty.isBlank()) {
            return InterviewDefaults.DIFFICULTY;
        }
        return difficulty.trim();
    }

    /**
     * 归一化方向名（LLM 输出的兜底处理）：
     * <ul>
     *   <li>空白时用 fallback（生成场景传知识库名），若 fallback 为空且 null 则返回 "未分类"（手动创建场景）</li>
     *   <li>否则去掉首尾空白</li>
     * </ul>
     */
    private String normalizeCategory(String category, String fallback) {
        if (category == null || category.isBlank()) {
            return fallback != null && !fallback.isBlank() ? fallback.trim() : "未分类";
        }
        return category.trim();
    }

    /** 字符串空串 → null 的统一转换（空字符串统一存储为 NULL） */
    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

}

