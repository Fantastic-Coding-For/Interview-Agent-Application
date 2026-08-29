package com.ywy.interviewagentapplication.modules.knowledgebase.service;

import com.ywy.interviewagentapplication.common.constant.CommonConstants.InterviewDefaults;
import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewQuestionDTO;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewSessionDTO;
import com.ywy.interviewagentapplication.modules.interview.service.InterviewSessionService;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.CreateKnowledgeBaseInterviewRequest;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseInterviewCapacityResponse;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseInterviewCapacityResponse.CategoryOption;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseInterviewCapacityResponse.FollowUpOption;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseQuestionEntity;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseQuestionFollowUpDTO;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseQuestionStatus;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

/**
 * 知识库面试服务：从题库抽取题目组建面试会话。
 *
 * <h3>与 LLM 实时出题面试的本质区别</h3>
 * <ul>
 *   <li>普通面试：创建时调用 LLM 生成题目（耗时 3-10 秒 + token 成本）</li>
 *   <li>知识库面试：从预生成题库抽取 ACTIVE 题目（毫秒级 + 零 LLM 成本）——
 *       题目经过人工审核，质量更可控</li>
 * </ul>
 * 知识库面试的答案评估仍然使用 LLM（复用面试模块的评估链路）。
 *
 * <h3>抽题算法</h3>
 * <ol>
 *   <li>筛选候选：ACTIVE 状态 + 难度匹配 + 方向匹配（可选）+ 追问数充足</li>
 *   <li>随机打乱候选（Collections.shuffle）</li>
 *   <li>取前 mainCount 道主问题</li>
 *   <li>每题从追问池随机抽 followUpCount 个追问（Fisher-Yates 局部洗牌）</li>
 * </ol>
 * 随机性的意义：同一知识库的两次面试得到不同的题目组合，候选人无法通过"背题"应付面试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseInterviewService {
    /** 追问数量上限（与请求校验 @Max(5) 一致） */
    private static final int MAX_FOLLOW_UP_COUNT = 5;

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<KnowledgeBaseQuestionFollowUpDTO>> FOLLOW_UP_LIST_TYPE =
            new TypeReference<>() {
            };

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseQuestionRepository questionRepository;
    private final InterviewSessionService interviewSessionService;
    private final ObjectMapper objectMapper;

    /**
     * 题源的内部结构：问题实体 + 解析后的 keyPoints 和追问列表。
     * 预解析（而非每次使用时解析 JSON）避免了重复的反序列化开销。
     */
    private record QuestionSource(KnowledgeBaseQuestionEntity question,
                                  List<String> keyPoints,
                                  List<KnowledgeBaseQuestionFollowUpDTO> followUps) {
    }

    /**
     * 从题库抽取题目创建面试会话。
     * <p>
     * <b>流程</b>：
     * <ol>
     *   <li>校验知识库存在</li>
     *   <li>筛选 ACTIVE 候选题（按方向和难度）</li>
     *   <li>过滤追问数不足的题目</li>
     *   <li>随机抽取 mainCount 道 + 每题随机抽追问</li>
     *   <li>委托面试模块的 createSessionFromQuestions 创建会话</li>
     * </ol>
     * <p>
     * 题目通过 {@code InterviewQuestionDTO.fromQuestionBank} 转换——
     * 携带题库的参考答案、要点、评分规则、来源上下文，
     * 使后续的答案评估有客观标准可依。
     *
     * @throws BusinessException 如果满足条件的候选题目数不足（错误信息说明缺什么）
     */
    public InterviewSessionDTO createSession(CreateKnowledgeBaseInterviewRequest request) {
        knowledgeBaseRepository.findById(request.knowledgeBaseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));

        String category = trimToNull(request.category());
        String difficulty = normalizeDifficulty(request.difficulty());
        int mainCount = request.mainQuestionCount();
        int followUpCount = request.followUpCount();

        List<KnowledgeBaseQuestionEntity> raw = selectActiveQuestions(
                request.knowledgeBaseId(), category, difficulty);

        List<QuestionSource> candidates = toQuestionSources(raw).stream()
                .filter(source -> source.followUps().size() >= followUpCount)
                .toList();

        if (candidates.size() < mainCount) {
            throw new BusinessException(
                    ErrorCode.INTERVIEW_QUESTION_INSUFFICIENT,
                    buildInsufficientMessage(
                            mainCount, candidates.size(), category, difficulty, followUpCount)
            );
        }

        List<QuestionSource> selected = new ArrayList<>(candidates);
        Collections.shuffle(selected);
        List<InterviewQuestionDTO> questions =
                buildQuestions(selected.subList(0, mainCount), followUpCount);

        log.info("创建知识库面试: kbId={}, category={}, difficulty={}, mainQuestions={}, totalQuestions={}",
                request.knowledgeBaseId(), category, difficulty, mainCount, questions.size());

        // skillId 已不再有业务含义，统一用默认值回填到面试会话表
        return interviewSessionService.createSessionFromQuestions(
                questions,
                request.llmProvider(),
                KnowledgeBaseQuestionEntity.DEFAULT_SKILL_ID,
                difficulty,
                request.knowledgeBaseId(),
                category
        );
    }

    /**
     * 查询知识库面试在指定方向、难度和主问题数下，不同追问题数策略的可用性（包含题目容量）。
     */
    public KnowledgeBaseInterviewCapacityResponse getCapacity(
            Long knowledgeBaseId,
            String category,
            String difficulty,
            int mainQuestionCount
    ) {
        knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));

        String normalizedCategory = trimToNull(category);
        String normalizedDifficulty = normalizeDifficulty(difficulty);
        // 不限方向，指定难度的题目
        List<QuestionSource> allSources = toQuestionSources(selectActiveQuestions(
                knowledgeBaseId, null, normalizedDifficulty));
        // 指定方向（category 为 null 则仍不限方向），指定难度的题目
        List<QuestionSource> scopedSources = allSources.stream()
                .filter(source -> normalizedCategory == null
                        || normalizedCategory.equals(source.question().getCategory()))
                .toList();

        // 计算指定方向（category 为 null 则仍不限方向），指定难度的题目中涉及的方向 + 在上述前提条件下方向对应的题目数。
        List<CategoryOption> categories = calculateCategoryOptions(allSources);
        List<FollowUpOption> followUpOptions = IntStream.rangeClosed(0, MAX_FOLLOW_UP_COUNT)
                .mapToObj(count -> {
                    int availableCount = (int) scopedSources.stream()
                            .filter(source -> source.followUps().size() >= count)
                            .count();
                    return new FollowUpOption(
                            count,  // 每道题需要的追问题数量
                            availableCount, // 满足追问题数量要求的题目数量
                            mainQuestionCount > 0 && availableCount >= mainQuestionCount    // 是否可以使用这种追问策略选项
                    );
                })
                .toList();

        return new KnowledgeBaseInterviewCapacityResponse(
                knowledgeBaseId,
                normalizedCategory,
                normalizedDifficulty,
                mainQuestionCount,
                categories,
                followUpOptions
        );
    }

    /**
     * 按方向过滤候选题。
     * category 为 null 时跨所有方向筛选（覆盖整个知识库的面试）。
     */
    private List<KnowledgeBaseQuestionEntity> selectActiveQuestions(
            Long knowledgeBaseId, String category, String difficulty) {
        if (category == null) {
            return questionRepository.findByKnowledgeBase_IdAndDifficultyAndStatusOrderByUpdatedAtDesc(
                    knowledgeBaseId, difficulty, KnowledgeBaseQuestionStatus.ACTIVE);
        }
        return questionRepository.findByKnowledgeBase_IdAndDifficultyAndCategoryAndStatusOrderByUpdatedAtDesc(
                knowledgeBaseId, difficulty, category, KnowledgeBaseQuestionStatus.ACTIVE);
    }

    /**
     * 组装面试问题列表，每个元素包含：主问题 + 随机抽取的追问。
     */
    private List<InterviewQuestionDTO> buildQuestions(List<QuestionSource> selected, int followUpCount) {
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        for (QuestionSource source : selected) {
            KnowledgeBaseQuestionEntity entity = source.question();
            int mainIndex = questions.size();
            questions.add(InterviewQuestionDTO.fromQuestionBank(
                    mainIndex,
                    entity.getQuestion(),
                    defaultString(entity.getType(), "KNOWLEDGE_BASE"),
                    defaultString(entity.getCategory(), "知识库"),
                    entity.getTopicSummary(),
                    entity.getReferenceAnswer(),
                    source.keyPoints(),
                    entity.getScoringRubric(),
                    entity.getSourceContext()
            ));

            // 在可用的追问池里随机抽 followUpCount 个，避免每次面试都问同一组追问
            List<KnowledgeBaseQuestionFollowUpDTO> picked = pickFollowUps(source.followUps(), followUpCount);
            for (KnowledgeBaseQuestionFollowUpDTO followUp : picked) {
                questions.add(new InterviewQuestionDTO(
                        questions.size(),
                        followUp.question(),
                        defaultString(entity.getType(), "KNOWLEDGE_BASE"),
                        defaultString(entity.getCategory(), "知识库追问"),
                        entity.getTopicSummary(),
                        null,
                        null,
                        null,
                        true,
                        mainIndex,
                        followUp.referenceAnswer(),
                        followUp.keyPoints(),
                        followUp.scoringRubric(),
                        entity.getSourceContext()
                ));
            }
        }
        return questions;
    }

    /**
     * 在追问池里随机抽取严格的 count 个，池容量不足时拒绝继续组装。
     * 使用 Fisher-Yates 复制列表后局部洗牌（只洗前 count 个位置），避免改动原列表顺序。
     */
    private List<KnowledgeBaseQuestionFollowUpDTO> pickFollowUps(
            List<KnowledgeBaseQuestionFollowUpDTO> pool, int count) {
        if (count <= 0) {
            return List.of();
        }
        if (pool == null || pool.size() < count) {
            throw new BusinessException(
                    ErrorCode.INTERVIEW_QUESTION_INSUFFICIENT,
                    "追问池在组装面试时发生变化，无法严格抽取 " + count + " 个追问"
            );
        }
        int n = count;
        if (n == pool.size()) {
            return new ArrayList<>(pool);
        }
        List<KnowledgeBaseQuestionFollowUpDTO> copy = new ArrayList<>(pool);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < n; i += 1) {
            int j = random.nextInt(i, copy.size());
            KnowledgeBaseQuestionFollowUpDTO tmp = copy.get(i);
            copy.set(i, copy.get(j));
            copy.set(j, tmp);
        }
        return copy.subList(0, n);
    }

    /**
     * 知识库问题实体列表 → QuestionSource 列表（预解析 JSON 字段）。
     * 单题解析失败时 keyPoints/followUps 返回空列表，不阻塞整体面试创建（该题只是失去参考信息）。
     */
    private List<QuestionSource> toQuestionSources(List<KnowledgeBaseQuestionEntity> questions) {
        return questions.stream()
                .map(question -> new QuestionSource(
                        question,
                        readStringList(question.getKeyPointsJson()),
                        readUsableFollowUps(question.getFollowUpsJson())
                ))
                .toList();
    }

    /**
     * 计算各方向的可用题目数（LinkedHashMap 保持首次出现顺序，最终按数量降序 + 名称升序排序）。
     */
    private List<CategoryOption> calculateCategoryOptions(List<QuestionSource> sources) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (QuestionSource source : sources) {
            String category = trimToNull(source.question().getCategory());
            if (category != null) {
                counts.merge(category, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new CategoryOption(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(CategoryOption::availableQuestionCount).reversed()
                        .thenComparing(CategoryOption::category))
                .toList();
    }

    /** 构建"题目不足"的可读错误信息，明确指出缺失的维度 */
    private String buildInsufficientMessage(
            int requiredCount,
            int availableCount,
            String category,
            String difficulty,
            int followUpCount
    ) {
        String direction = category == null ? "全部方向" : category;
        return "需要 " + requiredCount + " 道主问题，但只有 " + availableCount
                + " 道同时满足：方向=" + direction
                + "、难度=" + difficulty
                + "、每题至少 " + followUpCount + " 个追问";
    }

    /**
     * 将 JSON 字符串反序列化为 List 结构，解析失败返回空列表
     */
    private List<String> readStringList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, STRING_LIST_TYPE);
        } catch (JacksonException e) {
            log.warn("解析题目要点失败: {}", e.getMessage());
            return List.of();
        }
    }

    private List<KnowledgeBaseQuestionFollowUpDTO> readUsableFollowUps(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            List<KnowledgeBaseQuestionFollowUpDTO> followUps =
                    objectMapper.readValue(value, FOLLOW_UP_LIST_TYPE);
            if (followUps == null) {
                return List.of();
            }
            return followUps.stream()
                    .filter(followUp -> followUp != null
                            && followUp.question() != null
                            && !followUp.question().isBlank())
                    .map(followUp -> new KnowledgeBaseQuestionFollowUpDTO(
                            followUp.question().trim(),
                            followUp.referenceAnswer(),
                            followUp.keyPoints(),
                            followUp.scoringRubric()
                    ))
                    .toList();
        } catch (JacksonException e) {
            log.warn("解析追问失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String normalizeDifficulty(String difficulty) {
        if (difficulty == null || difficulty.isBlank()) {
            return InterviewDefaults.DIFFICULTY;
        }
        return difficulty.trim();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String defaultString(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}

