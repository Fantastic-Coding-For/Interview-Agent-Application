package com.ywy.interviewagentapplication.modules.interview.service;

import com.ywy.interviewagentapplication.common.constant.CommonConstants.InterviewDefaults;
import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.common.model.AsyncTaskStatus;
import com.ywy.interviewagentapplication.modules.interview.model.HistoricalQuestion;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewAnswerEntity;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewQuestionDTO;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewReportDTO;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewSessionEntity;
import com.ywy.interviewagentapplication.modules.interview.repository.InterviewAnswerRepository;
import com.ywy.interviewagentapplication.modules.interview.repository.InterviewSessionRepository;
import com.ywy.interviewagentapplication.modules.resume.model.ResumeEntity;
import com.ywy.interviewagentapplication.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 面试持久化服务：面试会话和答案的数据库操作封装。
 *
 * <h3>架构定位</h3>
 * 本 Service 是面试模块的<b>数据库访问门面</b>（Facade），向上层屏蔽了
 * Repository 层的细节。它不包含业务逻辑判断，只负责：
 * <ul>
 *   <li>JPA 实体的 CRUD 操作</li>
 *   <li>JSON ↔ Java 对象的序列化/反序列化</li>
 *   <li>事务管理（@Transactional）</li>
 *   <li>实体关联的建立（Session ↔ Resume）</li>
 * </ul>
 *
 * <h3>为什么需要这个中间层？</h3>
 * 直接在 SessionService 中调用 Repository 会：
 * <ul>
 *   <li>让 SessionService 过于臃肿（既有业务逻辑又有持久化细节）</li>
 *   <li>导致持久化逻辑在多处重复（如"保存答案"在 submitAnswer 和 saveAnswer 中都需要）</li>
 *   <li>难以统一事务边界（@Transactional 散布在各处）</li>
 * </ul>
 * 中间层将持久化逻辑集中管理，上层 Service 只需调用 PersistenceService 的方法，
 * 不需要知道底层是 JPA 还是 MyBatis，也不需要关心事务传播行为。
 *
 * <h3>事务策略</h3>
 * 所有写操作标注 {@code @Transactional(rollbackFor = Exception.class)}：
 * <ul>
 *   <li>{@code rollbackFor = Exception.class}：默认只对 RuntimeException 回滚，
 *       显式指定确保所有异常都触发回滚</li>
 *   <li>读操作不标注 @Transactional：JPA 的自动提交模式已足够</li>
 * </ul>
 *
 * <h3>Cascade 依赖说明</h3>
 * 删除操作依赖 JPA 实体上配置的 {@code cascade = CascadeType.ALL, orphanRemoval = true}：
 * 删除 Session 时会自动级联删除其关联的 Answers。如果变更了 JPA 映射，
 * 需要同步检查此处的删除逻辑是否仍然正确。
 *
 * @see InterviewSessionRepository JPA Repository
 * @see InterviewAnswerRepository JPA Repository
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewPersistenceService {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewAnswerRepository answerRepository;
    private final ResumeRepository resumeRepository;
    private final ObjectMapper objectMapper;

    /**
     * 保存新的面试会话（支持可选简历）——标准面试（sourceType = NORMAL）
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionEntity saveSession(String sessionId, Long resumeId,
                                              int totalQuestions,
                                              List<InterviewQuestionDTO> questions,
                                              String llmProvider,
                                              String skillId,
                                              String difficulty) {
        return saveSession(sessionId, resumeId, totalQuestions, questions, llmProvider, skillId, difficulty,
                "NORMAL", null, null);
    }

    /**
     * 保存新的面试会话——知识库面试（sourceType = KNOWLEDGE_BASE）。
     * 需要额外记录 knowledgeBaseId 和 interviewCategory。
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionEntity saveSession(String sessionId, Long resumeId,
                                              int totalQuestions,
                                              List<InterviewQuestionDTO> questions,
                                              String llmProvider,
                                              String skillId,
                                              String difficulty,
                                              String sourceType,
                                              Long knowledgeBaseId,
                                              String interviewCategory) {
        return saveSessionInternal(sessionId, resumeId, totalQuestions, questions, llmProvider,
                skillId, difficulty, sourceType, knowledgeBaseId, interviewCategory, null);
    }

    /**
     * 幂等保存面试会话：带 requestId 的标准面试。
     * <p>
     * requestId 存储在 session 实体中，数据库层有唯一约束（UNIQUE INDEX）。
     * 重复插入会触发 DataIntegrityViolationException，
     * 上层通过 catch 异常并查询已有记录实现幂等。
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewSessionEntity saveIdempotentSession(String sessionId, Long resumeId,
                                                        int totalQuestions,
                                                        List<InterviewQuestionDTO> questions,
                                                        String llmProvider,
                                                        String skillId,
                                                        String difficulty,
                                                        String requestId) {
        return saveSessionInternal(sessionId, resumeId, totalQuestions, questions, llmProvider,
                skillId, difficulty, "NORMAL", null, null, requestId);
    }


    /**
     * 保存会话的内部实现（被所有公开方法委托）。
     * <p>
     * <b>问题列表的序列化</b>：
     * 面试问题列表在数据库中存储为 JSON 字符串（questionsJson 列），
     * 而非独立的问题表。这样设计的原因：
     * <ul>
     *   <li>问题数据与面试强绑定：没有跨会话查询问题的需求</li>
     *   <li>简化了数据模型：不需要 Question 实体和 QuestionRepository</li>
     *   <li>JSON 字段便于版本演化：新增字段不需要 DDL 变更</li>
     * </ul>
     * 代价：无法对问题内容做 SQL 查询（如"统计出现次数最多的题目"），但这种需求在当前业务中不存在。
     * <p>
     * <b>resumeId 可选</b>：
     * 无简历面试（通用模式）时无需关联简历。简历实体从 Repository 加载后通过 {@code session.setResume()} 建立 JPA 关联。
     *
     * @param sessionId        会话唯一标识
     * @param resumeId         简历ID（可为 null）
     * @param totalQuestions   总题数
     * @param questions        问题列表
     * @param llmProvider      LLM 提供商
     * @param skillId          Skill 标识
     * @param difficulty       难度等级
     * @param sourceType       来源类型（NORMAL/KNOWLEDGE_BASE）
     * @param knowledgeBaseId  知识库ID
     * @param interviewCategory 面试类别
     * @param requestId        幂等标识（可为 null）
     * @return 持久化后的会话实体
     */
    private InterviewSessionEntity saveSessionInternal(String sessionId, Long resumeId,
                                                       int totalQuestions,
                                                       List<InterviewQuestionDTO> questions,
                                                       String llmProvider,
                                                       String skillId,
                                                       String difficulty,
                                                       String sourceType,
                                                       Long knowledgeBaseId,
                                                       String interviewCategory,
                                                       String requestId) {
        try {
            InterviewSessionEntity session = new InterviewSessionEntity();
            session.setSessionId(sessionId);
            session.setRequestId(requestId);
            session.setTotalQuestions(totalQuestions);
            session.setCurrentQuestionIndex(0);
            session.setStatus(InterviewSessionEntity.SessionStatus.CREATED);
            session.setQuestionsJson(objectMapper.writeValueAsString(questions));
            session.setLlmProvider(llmProvider != null ? llmProvider : "default");
            session.setSkillId(skillId != null ? skillId : InterviewDefaults.SKILL_ID);
            session.setDifficulty(difficulty != null ? difficulty : InterviewDefaults.DIFFICULTY);
            session.setSourceType(sourceType != null ? sourceType : "NORMAL");
            session.setKnowledgeBaseId(knowledgeBaseId);
            session.setInterviewCategory(interviewCategory);

            // 简历可选：有 resumeId 则关联简历
            if (resumeId != null) {
                Optional<ResumeEntity> resumeOpt = resumeRepository.findById(resumeId);
                resumeOpt.ifPresent(session::setResume);
            }

            InterviewSessionEntity saved = sessionRepository.save(session);
            log.info("面试会话已保存: sessionId={}, skillId={}, resumeId={}, sourceType={}",
                    sessionId, skillId, resumeId, session.getSourceType());

            return saved;
        } catch (JacksonException e) {
            log.error("序列化问题列表失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存会话失败");
        }
    }

    /**
     * 更新会话状态到数据库。
     * <p>
     * 当状态变为 COMPLETED 或 EVALUATED 时自动设置 completedAt 时间戳。
     *
     * @param sessionId 会话ID
     * @param status    新状态
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateSessionStatus(String sessionId, InterviewSessionEntity.SessionStatus status) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            InterviewSessionEntity session = sessionOpt.get();
            session.setStatus(status);
            if (status == InterviewSessionEntity.SessionStatus.COMPLETED ||
                    status == InterviewSessionEntity.SessionStatus.EVALUATED) {
                session.setCompletedAt(LocalDateTime.now());
            }
            sessionRepository.save(session);
        }
    }

    /**
     * 更新会话评估状态到数据库。
     * <p>
     * 错误信息超过 500 字符时截断，因为 evaluateError 列有长度限制，
     * 但异常堆栈可能非常长。保留前 500 字符足够定位问题。
     * 可通过将 error 设为 null 来清除错误信息（状态变为 PROCESSING/COMPLETED 时调用）。
     *
     * @param sessionId 会话ID
     * @param status    评估状态（PENDING/PROCESSING/COMPLETED/FAILED）
     * @param error     错误信息（可为 null，表示没有错误信息）
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            InterviewSessionEntity session = sessionOpt.get();
            session.setEvaluateStatus(status);
            if (error != null) {
                session.setEvaluateError(error.length() > 500 ? error.substring(0, 500) : error);
            } else {
                session.setEvaluateError(null);
            }
            sessionRepository.save(session);
            log.debug("评估状态已更新: sessionId={}, status={}", sessionId, status);
        }
    }

    /**
     * 更新当前答题进度索引，同时将状态设为 IN_PROGRESS
     * @param sessionId 会话ID
     * @param index     新的当前题目索引（指向下一道待答的题）
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateCurrentQuestionIndex(String sessionId, int index) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            InterviewSessionEntity session = sessionOpt.get();
            session.setCurrentQuestionIndex(index);
            session.setStatus(InterviewSessionEntity.SessionStatus.IN_PROGRESS);
            sessionRepository.save(session);
        }
    }

    /**
     * 保存/更新面试答案（UPSERT 语义）。
     * <p>
     * <b>UPSERT 实现</b>：
     * 先按 sessionId + questionIndex 查找已有答题记录，
     * 找到则更新，未找到则创建：同一个问题多次提交时复用同一条记录，避免重复答题记录堆积。
     * <p>
     * <b>注意</b>：此方法不更新 score 和 feedback 字段（传入 0 和 null），评分在评估阶段单独更新（通过 saveReport 方法）。
     *
     * @param sessionId     会话ID
     * @param questionIndex  问题索引
     * @param question      问题文本
     * @param category      问题分类
     * @param userAnswer    用户回答
     * @param score         评分（存储时为 0，评估阶段更新）
     * @param feedback      评语（存储时为 null，评估阶段更新）
     * @return 保存后的答案实体
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewAnswerEntity saveAnswer(String sessionId, int questionIndex,
                                            String question, String category,
                                            String userAnswer, int score, String feedback) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        InterviewAnswerEntity answer = answerRepository
                .findBySession_SessionIdAndQuestionIndex(sessionId, questionIndex)
                .orElseGet(() -> {
                    InterviewAnswerEntity created = new InterviewAnswerEntity();
                    created.setSession(sessionOpt.get());
                    created.setQuestionIndex(questionIndex);
                    return created;
                });

        answer.setQuestion(question);
        answer.setCategory(category);
        answer.setUserAnswer(userAnswer);
        answer.setScore(score);
        answer.setFeedback(feedback);

        InterviewAnswerEntity saved = answerRepository.save(answer);
        log.info("面试答案已保存: sessionId={}, questionIndex={}, score={}",
                sessionId, questionIndex, score);

        return saved;
    }

    /**
     * 保存评估报告及相关答案（评分 + 反馈 + 参考答案 + 关键要点）。
     *
     * <h4>保存流程</h4>
     * <ol>
     *   <li>更新会话实体的评估字段（overallScore、overallFeedback、
     *       strengthsJson、improvementsJson、referenceAnswersJson、status）</li>
     *   <li>加载已存在的答案记录，建立 questionIndex → AnswerEntity 索引</li>
     *   <li>加载评估报告的参考答案和逐题评分，建立索引</li>
     *   <li>遍历所有题目评估结果，<b>包括未回答的题目</b>：
     *     <ul>
     *       <li>已有答案记录 → 更新评分、反馈、参考答案</li>
     *       <li>无答案记录 → 创建新记录（userAnswer = null，代表未回答但仍有评分）</li>
     *     </ul>
     *   </li>
     *   <li>批量保存所有答案记录</li>
     * </ol>
     *
     * <h4>为什么要为未回答的题目也创建答案记录？</h4>
     * 评估报告需要展示"第 3 题：未回答 → 得分 0"。
     * 如果不创建记录，报告中的评分信息无法关联到具体的答案实体，
     * 后续查询详情时需要额外处理"有评分但无答案记录"的异常情况。
     *
     * @param sessionId 会话ID
     * @param report    评估报告（包含评分、反馈、参考答案）
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveReport(String sessionId, InterviewReportDTO report) {
        try {
            Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
            if (sessionOpt.isEmpty()) {
                log.warn("会话不存在: {}", sessionId);
                return;
            }

            InterviewSessionEntity session = sessionOpt.get();
            session.setOverallScore(report.overallScore());
            session.setOverallFeedback(report.overallFeedback());
            session.setStrengthsJson(objectMapper.writeValueAsString(report.strengths()));
            session.setImprovementsJson(objectMapper.writeValueAsString(report.improvements()));
            session.setReferenceAnswersJson(objectMapper.writeValueAsString(report.referenceAnswers()));
            session.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
            session.setCompletedAt(LocalDateTime.now());

            sessionRepository.save(session);

            // 查询已存在的答案，建立索引
            List<InterviewAnswerEntity> existingAnswers = answerRepository.findBySession_SessionIdOrderByQuestionIndex(sessionId);
            java.util.Map<Integer, InterviewAnswerEntity> answerMap = existingAnswers.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            InterviewAnswerEntity::getQuestionIndex,
                            a -> a,
                            (a1, a2) -> a1
                    ));

            // 建立参考答案索引
            java.util.Map<Integer, InterviewReportDTO.ReferenceAnswer> refAnswerMap = report.referenceAnswers().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            InterviewReportDTO.ReferenceAnswer::questionIndex,
                            r -> r,
                            (r1, r2) -> r1
                    ));

            List<InterviewAnswerEntity> answersToSave = new java.util.ArrayList<>();

            // 遍历所有评估结果，更新或创建答案记录
            for (InterviewReportDTO.QuestionEvaluation eval : report.questionDetails()) {
                InterviewAnswerEntity answer = answerMap.get(eval.questionIndex());

                if (answer == null) {
                    // 未回答的题目，创建新记录
                    answer = new InterviewAnswerEntity();
                    answer.setSession(session);
                    answer.setQuestionIndex(eval.questionIndex());
                    answer.setQuestion(eval.question());
                    answer.setCategory(eval.category());
                    answer.setUserAnswer(null);  // 未回答
                    log.debug("为未回答的题目 {} 创建答案记录", eval.questionIndex());
                }

                // 更新评分和反馈
                answer.setScore(eval.score());
                answer.setFeedback(eval.feedback());

                // 设置参考答案和关键点
                InterviewReportDTO.ReferenceAnswer refAns = refAnswerMap.get(eval.questionIndex());
                if (refAns != null) {
                    answer.setReferenceAnswer(refAns.referenceAnswer());
                    if (refAns.keyPoints() != null && !refAns.keyPoints().isEmpty()) {
                        answer.setKeyPointsJson(objectMapper.writeValueAsString(refAns.keyPoints()));
                    }
                }

                answersToSave.add(answer);
            }

            answerRepository.saveAll(answersToSave);
            log.info("面试报告已保存: sessionId={}, score={}, 答案数={}",
                    sessionId, report.overallScore(), answersToSave.size());

        } catch (JacksonException e) {
            log.error("序列化报告失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 根据会话ID获取会话（查数据库）
     */
    public Optional<InterviewSessionEntity> findBySessionId(String sessionId) {
        return sessionRepository.findBySessionId(sessionId);
    }

    /** 根据幂等标识查询（用于幂等创建的 DB 级检查） */
    public Optional<InterviewSessionEntity> findByRequestId(String requestId) {
        return sessionRepository.findByRequestId(requestId);
    }

    /** 查询某简历的所有面试记录（按时间降序） */
    public List<InterviewSessionEntity> findByResumeId(Long resumeId) {
        return sessionRepository.findByResumeIdOrderByCreatedAtDesc(resumeId);
    }

    /**
     * 获取所有面试记录（按创建时间降序）
     */
    public List<InterviewSessionEntity> findAll() {
        return sessionRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * 删除某简历的所有面试会话（级联删除关联答案）。
     * <p>
     * 级联删除依赖 JPA 实体上的 {@code cascade = CascadeType.ALL, orphanRemoval = true} 配置。
     * 如果 InterviewSessionEntity 上的级联配置被移除，则此方法不会级联删除。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSessionsByResumeId(Long resumeId) {
        List<InterviewSessionEntity> sessions = sessionRepository.findByResumeIdOrderByCreatedAtDesc(resumeId);
        if (!sessions.isEmpty()) {
            sessionRepository.deleteAll(sessions);
            log.info("已删除 {} 个面试会话（包含所有答案）", sessions.size());
        }
    }

    /**
     * 删除单个面试会话
     * 由于 InterviewSessionEntity 设置了cascade = CascadeType.ALL, orphanRemoval = true
     * 删除会话会自动删除关联的答案
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSessionBySessionId(String sessionId) {
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionId(sessionId);
        if (sessionOpt.isPresent()) {
            sessionRepository.delete(sessionOpt.get());
            log.info("已删除面试会话: sessionId={}", sessionId);
        } else {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }
    }

    /**
     * 查找未完成的面试会话（CREATED 或 IN_PROGRESS 状态）。
     * <p>
     * 如果有多个（数据异常），取最新创建的。
     *
     * @param resumeId 简历ID
     * @return 包含未完成会话的 Optional
     */
    public Optional<InterviewSessionEntity> findUnfinishedSession(Long resumeId) {
        List<InterviewSessionEntity.SessionStatus> unfinishedStatuses = List.of(
                InterviewSessionEntity.SessionStatus.CREATED,
                InterviewSessionEntity.SessionStatus.IN_PROGRESS
        );
        return sessionRepository.findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(resumeId, unfinishedStatuses);
    }

    /**
     * 根据会话ID查找所有答案（按问题索引升序）
     */
    public List<InterviewAnswerEntity> findAnswersBySessionId(String sessionId) {
        return answerRepository.findBySession_SessionIdOrderByQuestionIndex(sessionId);
    }

    /** 历史问题加载的上限设置：防止加载过多历史会话导致内存和性能问题 */
    private static final int MAX_HISTORICAL_QUESTIONS = 60;

    /**
     * 获取历史提问列表：用于出题时的知识点去重。
     *
     * <h4>查询策略</h4>
     * <ul>
     *   <li><b>有 resumeId</b>：精确匹配 resumeId + skillId，保证"同一候选人同一方向不重复"</li>
     *   <li><b>无 resumeId</b>：仅按 skillId 查询，通用模式下的全局去重</li>
     * </ul>
     * 使用 top 10 个最近会话（而非全部）：最近最相关，且限制了数据量。
     *
     * <h4>去重策略</h4>
     * <ol>
     *   <li>从历史会话的问题 JSON 中解析出主问题（排除追问）</li>
     *   <li>使用 {@link LinkedHashSet} 按问题原文去重：相同文字的问题只保留第一次</li>
     *   <li>限制到 {@value #MAX_HISTORICAL_QUESTIONS} 条：防止历史数据过多导致
     *       LLM 提示词过长</li>
     * </ol>
     * <p>
     * 使用问题原文（而非 topicSummary）去重的原因：历史问题的 topicSummary
     * 可能是旧版数据格式（没有该字段），原文是最可靠的比较基准。
     *
     * @param skillId  Skill 标识
     * @param resumeId 简历ID（通用模式可为 null）
     * @return 去重后的历史问题列表（最多 MAX_HISTORICAL_QUESTIONS 条主问题）
     */
    public List<HistoricalQuestion> getHistoricalQuestions(String skillId, Long resumeId) {
        List<InterviewSessionEntity> sessions;
        if (resumeId != null) {
            sessions = sessionRepository.findTop10ByResumeIdAndSkillIdOrderByCreatedAtDesc(resumeId, skillId);
        } else {
            sessions = sessionRepository.findTop10BySkillIdOrderByCreatedAtDesc(skillId);
        }

        log.info("加载历史题目: skillId={}, resumeId={}, 查到 {} 个历史会话", skillId, resumeId, sessions.size());

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<HistoricalQuestion> result = sessions.stream()
                .map(InterviewSessionEntity::getQuestionsJson)
                .filter(json -> json != null && !json.isEmpty())
                .flatMap(json -> {
                    try {
                        List<InterviewQuestionDTO> questions = objectMapper.readValue(json,
                                new TypeReference<List<InterviewQuestionDTO>>() {});
                        return questions.stream()
                                .filter(q -> !q.isFollowUp())
                                .map(q -> new HistoricalQuestion(q.question(), q.type(), q.topicSummary()));
                    } catch (Exception e) {
                        log.error("解析历史问题JSON失败", e);
                        return java.util.stream.Stream.<HistoricalQuestion>empty();
                    }
                })
                .filter(hq -> seen.add(hq.question()))
                .limit(MAX_HISTORICAL_QUESTIONS)
                .toList();

        log.info("历史题目加载完成: 去重后 {} 道主问题，按分类: {}", result.size(),
                result.stream().collect(java.util.stream.Collectors.groupingBy(
                        hq -> hq.type() != null ? hq.type() : "GENERAL",
                        java.util.stream.Collectors.counting())));

        return result;
    }
}


