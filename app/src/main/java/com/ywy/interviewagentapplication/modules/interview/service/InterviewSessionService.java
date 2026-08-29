package com.ywy.interviewagentapplication.modules.interview.service;

import com.ywy.interviewagentapplication.common.constant.CommonConstants.InterviewDefaults;
import com.ywy.interviewagentapplication.common.ai.LlmProviderRegistry;
import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.common.model.AsyncTaskStatus;
import com.ywy.interviewagentapplication.infrastructure.redis.InterviewSessionCache;
import com.ywy.interviewagentapplication.infrastructure.redis.InterviewSessionCache.CachedSession;
import com.ywy.interviewagentapplication.infrastructure.redis.RedisService;
import com.ywy.interviewagentapplication.modules.interview.listener.EvaluateStreamProducer;
import com.ywy.interviewagentapplication.modules.interview.model.CreateInterviewRequest;
import com.ywy.interviewagentapplication.modules.interview.model.HistoricalQuestion;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewAnswerEntity;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewQuestionDTO;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewReportDTO;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewSessionDTO;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewSessionEntity;
import com.ywy.interviewagentapplication.modules.interview.model.SubmitAnswerRequest;
import com.ywy.interviewagentapplication.modules.interview.model.SubmitAnswerResponse;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewSessionDTO.SessionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 面试会话管理服务。定义了面试会话的生命周期。
 *
 * <h3>架构定位</h3>
 * 本 Service 是面试模块的<b>核心编排者</b>，管理会话从创建到评估的完整生命周期：
 * <pre>
 *   createSession → [getCurrentQuestion → submitAnswer] × N → completeInterview → generateReport
 * </pre>
 *
 * <h3>双层存储架构（Cache-Aside 模式）</h3>
 * 会话数据同时存在于 Redis（热缓存）和数据库（持久化存储）：
 * <ul>
 *   <li><b>Redis</b>：存放进行中的会话数据（问题列表、当前进度、答案），
 *       TTL 24 小时后自动过期。读取延迟 < 1ms。</li>
 *   <li><b>数据库</b>：所有会话的持久化记录，Redis 中的数据是 DB 的"热副本"。
 *       缓存未命中时自动从 DB 恢复（Cache Miss → DB → Redis）。</li>
 * </ul>
 * 读写策略：<b>写时穿透（Write-Through）</b>，先写 DB，成功后更新 Redis。
 * 这样即使 Redis 更新失败，下次读取也能从 DB 恢复（牺牲一点性能换取数据安全）。
 *
 * <h3>幂等性设计</h3>
 * 通过 {@code requestId} 实现创建操作的幂等性：
 * <ol>
 *   <li>分布式锁（185s 等待 + 600s 持有）防止并发重复创建</li>
 *   <li>Redis 缓存创建结果（1 天 TTL）→ 快速幂等返回</li>
 *   <li>数据库查询兜底（Redis 过期后仍能从 DB 恢复）</li>
 * </ol>
 * 锁的等待时间 185s 的设计：考虑到 LLM 出题可能需要 60-120s，
 * 185s 足够完成一次正常的创建流程。600s 的持有时间防止 LLM 调用超时导致锁提前释放。
 *
 * <h3>异步评估触发</h3>
 * 面试完成（提取交卷/提交最后一道题）后，通过 Redis Stream 发送评估任务：
 * 评估不阻塞答题流程，候选人提交最后一道题后立即看到"已完成"，
 * 评估在后台异步进行，完成后通过轮询或 WebSocket 通知前端。
 *
 * @see InterviewSessionCache Redis 缓存层
 * @see InterviewQuestionService 问题生成
 * @see EvaluateStreamProducer 异步评估消息生产者
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewSessionService {
    /** 创建会话的分布式锁前缀 */
    private static final String CREATE_LOCK_PREFIX = "interview:create:";
    /** 幂等创建的结果缓存前缀 */
    private static final String CREATE_RESULT_PREFIX = "interview:create:result:";
    /** 幂等结果的缓存时间（1天） */
    private static final Duration CREATE_RESULT_TTL = Duration.ofDays(1);

    private final InterviewQuestionService questionService;
    private final AnswerEvaluationService evaluationService;
    private final InterviewPersistenceService persistenceService;
    private final InterviewSessionCache sessionCache;
    private final ObjectMapper objectMapper;
    private final EvaluateStreamProducer evaluateStreamProducer;
    private final LlmProviderRegistry llmProviderRegistry;
    private final RedisService redisService;

    /**
     * 创建面试会话（统一入口），并返回会话信息。
     * <p>根据是否带有 requestId（创建请求幂等键） 决定是否幂等创建。</p>
     *
     * <h4>为什么需要 requestId？</h4>
     * 前端在创建面试的页面可能会因为网络重试、用户双击等原因多次提交。
     * 如果不做幂等，每次提交都会生成一个新的面试会话（含 LLM 出题调用），
     * 浪费 token 且造成数据混乱。requestId 由前端生成（UUID），保证同一次操作的多次提交只创建一个会话。
     *
     * @param request 创建请求（含 Skill、难度、题目数、简历、可选的 requestId）
     * @return 新创建或已存在的会话 DTO
     */
    public InterviewSessionDTO createSession(CreateInterviewRequest request) {
        String requestId = normalizeRequestId(request.requestId());
        if (requestId == null) {
            return createSessionInternal(request);
        }

        return redisService.executeWithLock(
                CREATE_LOCK_PREFIX + requestId,
                185,
                600,
                TimeUnit.SECONDS,
                () -> createIdempotentSession(request, requestId)
        );
    }

    /**
     * 创建面试会话，并返回会话信息。会话创建前存在三级检查机制，尝试复用已有结果：
     * <ol>
     *   <li>Redis 缓存（最快，< 1ms）</li>
     *   <li>数据库查询（次快，< 10ms）</li>
     *   <li>全新创建（最慢，含 LLM 调用，3-10s）</li>
     * </ol>
     */
    private InterviewSessionDTO createIdempotentSession(CreateInterviewRequest request, String requestId) {
        String resultKey = CREATE_RESULT_PREFIX + requestId;
        String cachedSessionId = redisService.get(resultKey);
        if (cachedSessionId != null) {
            log.info("复用缓存中的幂等创建请求: requestId={}, sessionId={}", requestId, cachedSessionId);
            return getSession(cachedSessionId);
        }

        Optional<InterviewSessionEntity> existing = persistenceService.findByRequestId(requestId);
        if (existing.isPresent()) {
            String existingSessionId = existing.get().getSessionId();
            log.info("从数据库恢复幂等创建请求: requestId={}, sessionId={}",
                    requestId, existingSessionId);
            redisService.set(resultKey, existingSessionId, CREATE_RESULT_TTL);
            return getSession(existingSessionId);
        }

        InterviewSessionDTO created = createSessionInternal(request, requestId);
        redisService.set(resultKey, created.sessionId(), CREATE_RESULT_TTL);
        return created;
    }

    /**
     * 创建面试会话，并返回会话信息。
     * @param request 创建请求
     */
    private InterviewSessionDTO createSessionInternal(CreateInterviewRequest request) {
        return createSessionInternal(request, null);
    }

    /**
     * 创建面试会话（核心实现），并返回会话信息。
     *
     * <h4>创建流程</h4>
     * <ol>
     *   <li>检查是否有未完成会话（resumeId + 非 forceCreate）</li>
     *   <li>生成 sessionId（UUID 前 16 位，兼顾唯一性和可读性）</li>
     *   <li>获取历史问题（用于 LLM 去重）</li>
     *   <li>调用 LLM 生成面试问题（最耗时步骤）</li>
     *   <li>持久化到数据库</li>
     *   <li>写入 Redis 缓存</li>
     * </ol>
     *
     * <h4>sessionId 设计</h4>
     * UUID 去掉连字符后取前 16 位：64 位熵，碰撞概率极低（约 10^-10），
     * 且长度友好（16 字符便于 URL 传输和日志查看）。
     *
     * <h4>先 DB 后 Redis 的写入顺序</h4>
     * 先确保数据安全落库，再写入易失缓存。如果反过来（先 Redis 后 DB），
     * 在 Redis 写入成功但 DB 写入失败时，会出现"缓存中有数据但 DB 中没有"的不一致。
     * 当前的顺序即使 Redis 写入失败，下次读取也会从 DB 恢复，最终一致。
     *
     * @param request   创建请求
     * @param requestId 幂等标识（可为 null）
     * @return 新创建/已存在的会话 DTO
     */
    private InterviewSessionDTO createSessionInternal(CreateInterviewRequest request, String requestId) {
        // 如果指定了resumeId且未强制创建，检查是否有未完成的会话
        if (request.resumeId() != null && !Boolean.TRUE.equals(request.forceCreate())) {
            Optional<InterviewSessionDTO> unfinishedOpt = findUnfinishedSession(request.resumeId());
            if (unfinishedOpt.isPresent()) {
                log.info("检测到未完成的面试会话，返回现有会话: resumeId={}, sessionId={}",
                        request.resumeId(), unfinishedOpt.get().sessionId());
                return unfinishedOpt.get();
            }
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String skillId = request.skillId() != null ? request.skillId() : InterviewDefaults.SKILL_ID;
        String difficulty = request.difficulty() != null ? request.difficulty() : InterviewDefaults.DIFFICULTY;

        log.info("创建新面试会话: {}, skill: {}, difficulty: {}, questionCount: {}, resumeId: {}",
                sessionId, skillId, difficulty, request.questionCount(), request.resumeId());

        // 获取历史问题（通用模式按 skillId 查询，有简历时按 resumeId + skillId 精确匹配）
        List<HistoricalQuestion> historicalQuestions =
                persistenceService.getHistoricalQuestions(skillId, request.resumeId());

        // 基于 Skill 生成面试问题
        List<InterviewQuestionDTO> questions = questionService.generateQuestionsBySkill(
                request.llmProvider(),
                skillId,
                difficulty,
                request.resumeText(),
                request.questionCount(),
                historicalQuestions,
                request.customCategories(),
                request.jdText()
        );

        if (requestId != null) {
            try {
                persistenceService.saveIdempotentSession(
                        sessionId,
                        request.resumeId(),
                        questions.size(),
                        questions,
                        request.llmProvider(),
                        skillId,
                        difficulty,
                        requestId
                );
            } catch (Exception e) {
                Optional<InterviewSessionEntity> concurrentlyCreated =
                        persistenceService.findByRequestId(requestId);
                if (concurrentlyCreated.isPresent()) {
                    return getSession(concurrentlyCreated.get().getSessionId());
                }
                log.error("持久化幂等面试会话失败: requestId={}", requestId, e);
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "创建面试会话失败，请重试");
            }
        } else {
            try {
                persistenceService.saveSession(sessionId, request.resumeId(),
                        questions.size(), questions, request.llmProvider(), skillId, difficulty);
            } catch (Exception e) {
                log.warn("保存面试会话到数据库失败: {}", e.getMessage());
            }
        }

        // 幂等请求必须先成功落库，再写入易失缓存，保证进程异常后可从数据库恢复。
        sessionCache.saveSession(
                sessionId,
                request.resumeText() != null ? request.resumeText() : "",
                request.resumeId(),
                null,
                null,
                questions,
                0,
                SessionStatus.CREATED
        );

        return new InterviewSessionDTO(
                sessionId,
                request.resumeText() != null ? request.resumeText() : "",
                questions.size(),
                0,
                questions,
                SessionStatus.CREATED,
                null,
                null
        );
    }

    /**
     * 从已有的问题列表创建会话（知识库面试专用）。
     * <p>
     * 与 {@link #createSession} 的区别：跳过 LLM 出题步骤，直接使用传入的问题。
     * 用于知识库面试场景：问题是从知识库文档中提取的，而非由 LLM 实时生成。
     *
     * @param questions         已有问题列表
     * @param llmProvider       LLM 提供商
     * @param skillId           Skill 标识
     * @param difficulty        难度
     * @param knowledgeBaseId   知识库 ID
     * @param interviewCategory 面试类别
     * @return 新创建的会话 DTO
     */
    public InterviewSessionDTO createSessionFromQuestions(List<InterviewQuestionDTO> questions,
                                                          String llmProvider,
                                                          String skillId,
                                                          String difficulty,
                                                          Long knowledgeBaseId,
                                                          String interviewCategory) {
        if (questions == null || questions.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "面试题目不能为空");
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        persistenceService.saveSession(
                sessionId, null, questions.size(), questions, llmProvider, skillId, difficulty,
                "KNOWLEDGE_BASE", knowledgeBaseId, interviewCategory);
        sessionCache.saveSession(sessionId, "", null, knowledgeBaseId, interviewCategory,
                questions, 0, SessionStatus.CREATED);

        return new InterviewSessionDTO(
                sessionId,
                "",
                questions.size(),
                0,
                questions,
                SessionStatus.CREATED,
                knowledgeBaseId,
                interviewCategory
        );
    }

    /**
     * 规范化 requestId：校验格式 + trim。
     * <p>
     * 安全约束：
     * <ul>
     *   <li>长度 8-64：太短不安全（容易被碰撞），太长无意义</li>
     *   <li>仅允许字母数字、下划线、连字符：防止路径遍历和注入攻击</li>
     * </ul>
     * 不满足格式的直接抛 BAD_REQUEST，不应静默接受不合规的 requestId。
     *
     * @param requestId 客户端提交的原始 requestId
     * @return 规范化后的 requestId，null 如果输入为空白
     */
    private String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }

        String normalized = requestId.trim();
        if (!normalized.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "requestId 格式不正确");
        }
        return normalized;
    }

    /**
     * 获取会话信息（优先从 Redis 缓存，未命中则从数据库恢复）。
     * <p>
     * 这是 Cache-Aside 模式的"读"端：Cache Miss → DB → 回填到 Cache。
     * 回填操作在 restoreSessionFromDatabase() 中完成，恢复会话的同时将其写入 Redis，
     * 下次读取即可命中缓存。
     *
     * @param sessionId 会话ID
     * @return 会话 DTO
     * @throws BusinessException 如果会话不存在（缓存和数据库都未找到）
     */
    public InterviewSessionDTO getSession(String sessionId) {
        // 1. 尝试从 Redis 缓存获取
        Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
        if (cachedOpt.isPresent()) {
            return toDTO(cachedOpt.get());
        }

        // 2. 缓存未命中，从数据库恢复
        CachedSession restoredSession = restoreSessionFromDatabase(sessionId);
        if (restoredSession == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        return toDTO(restoredSession);
    }

    /**
     * 查找并恢复未完成的面试会话（"继续面试"的核心逻辑）。
     * <p>
     * 查找路径与 getSession 相同：Redis → DB。
     * 不同的是：①按 resumeId 查找而非 sessionId；②未找到返回 empty 而非抛异常。
     *
     * @param resumeId 简历ID
     * @return 包含未完成会话 DTO 的 Optional
     */
    public Optional<InterviewSessionDTO> findUnfinishedSession(Long resumeId) {
        try {
            // 1. 先从 Redis 缓存查找
            Optional<String> cachedSessionIdOpt = sessionCache.findUnfinishedSessionId(resumeId);
            if (cachedSessionIdOpt.isPresent()) {
                String sessionId = cachedSessionIdOpt.get();
                Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
                if (cachedOpt.isPresent()) {
                    log.debug("从 Redis 缓存找到未完成会话: resumeId={}, sessionId={}", resumeId, sessionId);
                    return Optional.of(toDTO(cachedOpt.get()));
                }
            }

            // 2. 缓存未命中，从数据库查找
            Optional<InterviewSessionEntity> entityOpt = persistenceService.findUnfinishedSession(resumeId);
            if (entityOpt.isEmpty()) {
                return Optional.empty();
            }

            InterviewSessionEntity entity = entityOpt.get();
            CachedSession restoredSession = restoreSessionFromEntity(entity);
            if (restoredSession != null) {
                return Optional.of(toDTO(restoredSession));
            }
        } catch (Exception e) {
            log.error("恢复未完成会话失败: {}", e.getMessage(), e);
        }
        return Optional.empty();
    }

    /**
     * 查找未完成会话，如果不存在则抛异常。
     * <p>
     * 此方法用于"确定有未完成会话"的场景，找不到即抛出异常。
     */
    public InterviewSessionDTO findUnfinishedSessionOrThrow(Long resumeId) {
        return findUnfinishedSession(resumeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND, "未找到未完成的面试会话"));
    }

    /**
     * 从数据库恢复会话并回填 Redis 缓存。
     *
     * @param sessionId 会话ID
     * @return 恢复后的 CachedSession，失败返回 null
     */
    private CachedSession restoreSessionFromDatabase(String sessionId) {
        try {
            Optional<InterviewSessionEntity> entityOpt = persistenceService.findBySessionId(sessionId);
            return entityOpt.map(this::restoreSessionFromEntity).orElse(null);
        } catch (Exception e) {
            log.error("从数据库恢复会话失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 将 JPA 会话实体回填到 Redis 缓存。
     * <p>
     * <b>恢复内容包括</b>：
     * <ol>
     *   <li>从 questionsJson 解析问题列表</li>
     *   <li>从答案表中恢复已保存的答案（withAnswer 写入问题对象）</li>
     *   <li>状态枚举转换（Entity 的 SessionStatus → DTO 的 SessionStatus）</li>
     *   <li>回填 Redis 缓存（saveSession 操作）</li>
     * </ol>
     *
     * @param entity JPA 实体
     * @return 成功恢复并缓存后的 CachedSession，失败返回 null
     */
    private CachedSession restoreSessionFromEntity(InterviewSessionEntity entity) {
        try {
            // 解析问题列表
            List<InterviewQuestionDTO> questions = objectMapper.readValue(
                    entity.getQuestionsJson(),
                    new TypeReference<>() {}
            );

            // 恢复已保存的答案
            List<InterviewAnswerEntity> answers = persistenceService.findAnswersBySessionId(entity.getSessionId());
            for (InterviewAnswerEntity answer : answers) {
                int index = answer.getQuestionIndex();
                if (index >= 0 && index < questions.size()) {
                    InterviewQuestionDTO question = questions.get(index);
                    questions.set(index, question.withAnswer(answer.getUserAnswer()));
                }
            }

            SessionStatus status = convertStatus(entity.getStatus());

            // 保存到 Redis 缓存
            sessionCache.saveSession(
                    entity.getSessionId(),
                    entity.getResume() != null ? entity.getResume().getResumeText() : "",
                    entity.getResume() != null ? entity.getResume().getId() : null,
                    entity.getKnowledgeBaseId(),
                    entity.getInterviewCategory(),
                    questions,
                    entity.getCurrentQuestionIndex(),
                    status
            );

            log.info("从数据库恢复会话到 Redis: sessionId={}, currentIndex={}, status={}",
                    entity.getSessionId(), entity.getCurrentQuestionIndex(), entity.getStatus());

            // 返回缓存的会话
            return sessionCache.getSession(entity.getSessionId()).orElse(null);
        } catch (Exception e) {
            log.error("恢复会话失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Entity 的 SessionStatus 枚举 → DTO 的 SessionStatus 枚举转换。
     * <p>
     * 两个枚举值完全相同但定义在不同的包中——Entity 层有自己的枚举
     * （JPA 映射需要），DTO 层也有（避免 DTO 依赖 Entity 层的枚举）。
     * 这是分层架构的代价：需要写转换代码，但保证了各层的独立演进。
     */
    private SessionStatus convertStatus(InterviewSessionEntity.SessionStatus status) {
        return switch (status) {
            case CREATED -> SessionStatus.CREATED;
            case IN_PROGRESS -> SessionStatus.IN_PROGRESS;
            case COMPLETED -> SessionStatus.COMPLETED;
            case EVALUATED -> SessionStatus.EVALUATED;
        };
    }

    /**
     * 获取当前问题的响应（包含完成状态）。
     * <p>
     * 返回 Map 而非固定 DTO 是因为有两种截然不同的响应形态：
     * <ul>
     *   <li>未完成：{@code {completed: false, question: {...}}}</li>
     *   <li>已完成：{@code {completed: true, message: "所有问题已回答完毕"}}</li>
     * </ul>
     * 使用 Map 避免了"可空字段"的 DTO 设计：要么有 question、要么有 message，不可能同时存在。
     */
    public Map<String, Object> getCurrentQuestionResponse(String sessionId) {
        InterviewQuestionDTO question = getCurrentQuestion(sessionId);
        if (question == null) {
            return Map.of(
                    "completed", true,
                    "message", "所有问题已回答完毕"
            );
        }
        return Map.of(
                "completed", false,
                "question", question
        );
    }

    /**
     * 获取当前待回答的问题。
     * <p>
     * 返回 null 表示所有问题已回答完毕（currentIndex >= questions.size()）。
     * <p>
     * <b>首次获取问题的副作用</b>：如果会话状态为 CREATED（刚创建还没开始答题），
     * 会自动将状态切换为 IN_PROGRESS（开始答题）。创建会话后，第一次获取问题即视为面试开始。
     *
     * @param sessionId 会话ID
     * @return 当前问题 DTO，全部答完返回 null
     */
    public InterviewQuestionDTO getCurrentQuestion(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        if (session.getCurrentIndex() >= questions.size()) {
            return null; // 所有问题已回答完
        }

        // 更新状态为进行中
        if (session.getStatus() == SessionStatus.CREATED) {
            session.setStatus(SessionStatus.IN_PROGRESS);
            sessionCache.updateSessionStatus(sessionId, SessionStatus.IN_PROGRESS);

            // 同步到数据库
            try {
                persistenceService.updateSessionStatus(sessionId,
                        InterviewSessionEntity.SessionStatus.IN_PROGRESS);
            } catch (Exception e) {
                log.warn("更新会话状态失败: {}", e.getMessage());
            }
        }

        return questions.get(session.getCurrentIndex());
    }

    /**
     * 提交答案，并自动返回下一题（若为最后一题，额外触发异步评估任务）。
     *
     * <h4>提交流程</h4>
     * <ol>
     *   <li>校验 questionIndex 合法性</li>
     *   <li>更新该题的答案（withAnswer）</li>
     *   <li>currentIndex 前进到下题</li>
     *   <li>持久化到 DB（答案 + 进度 + 状态）</li>
     *   <li>更新 Redis 缓存（问题列表 + 进度 + 状态）</li>
     *   <li>如果是最后一题 → 触发异步评估任务</li>
     * </ol>
     *
     * <h4>为什么完成后不立即同步生成报告？</h4>
     * LLM 评估可能需要 30-60 秒。如果在 HTTP 请求中同步等待，
     * 用户会看到浏览器一直转圈。改为异步模式：完成 → 入队评估任务 →
     * 立即返回"已完成"→ 前端轮询报告状态。
     *
     * @param request 包含 sessionId、questionIndex、answer 的提交请求
     * @return 提交结果（是否有下一题、下一题的内容、进度）
     */
    public SubmitAnswerResponse submitAnswer(SubmitAnswerRequest request) {
        CachedSession session = getOrRestoreSession(request.sessionId());
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        int index = request.questionIndex();
        if (index < 0 || index >= questions.size()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题索引: " + index);
        }

        // 更新问题答案
        InterviewQuestionDTO question = questions.get(index);
        InterviewQuestionDTO answeredQuestion = question.withAnswer(request.answer());
        questions.set(index, answeredQuestion);

        // 移动到下一题
        int newIndex = index + 1;

        // 检查是否全部完成
        boolean hasNextQuestion = newIndex < questions.size();
        InterviewQuestionDTO nextQuestion = hasNextQuestion ? questions.get(newIndex) : null;

        SessionStatus newStatus = hasNextQuestion ? SessionStatus.IN_PROGRESS : SessionStatus.COMPLETED;

        persistSubmittedAnswer(request, index, question, newIndex, newStatus);

        // 更新 Redis 缓存。DB 已经持久化成功，缓存失败时可由后续读取从数据库恢复。
        sessionCache.updateQuestions(request.sessionId(), questions);
        sessionCache.updateCurrentIndex(request.sessionId(), newIndex);
        if (newStatus == SessionStatus.COMPLETED) {
            sessionCache.updateSessionStatus(request.sessionId(), SessionStatus.COMPLETED);
            enqueueEvaluationTask(request.sessionId());
        }

        log.info("会话 {} 提交答案: 问题{}, 剩余{}题",
                request.sessionId(), index, questions.size() - newIndex);

        return new SubmitAnswerResponse(
                hasNextQuestion,
                nextQuestion,
                newIndex,
                questions.size()
        );
    }

    /**
     * 持久化已提交的答案到数据库。
     * <p>
     * 执行三个 DB 操作：保存答案记录、更新进度索引、更新会话状态。
     * 三个操作在一个事务上下文中（由调用方的事务管理器保证）。
     * 任何一步失败都抛异常回滚。
     * <p>
     * 注意：评分（score）在保存时为 0，此处的 0 表示"未评分"而非"得了 0 分"。
     */
    private void persistSubmittedAnswer(SubmitAnswerRequest request, int index,
                                        InterviewQuestionDTO question, int newIndex,
                                        SessionStatus newStatus) {
        try {
            persistenceService.saveAnswer(
                    request.sessionId(), index,
                    question.question(), question.category(),
                    request.answer(), 0, null  // 报告生成时才会统一为题目打分
            );
            persistenceService.updateCurrentQuestionIndex(request.sessionId(), newIndex);
            persistenceService.updateSessionStatus(request.sessionId(),
                    newStatus == SessionStatus.COMPLETED
                            ? InterviewSessionEntity.SessionStatus.COMPLETED
                            : InterviewSessionEntity.SessionStatus.IN_PROGRESS);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("保存答案到数据库失败: sessionId={}, questionIndex={}",
                    request.sessionId(), index, e);
            throw new BusinessException(ErrorCode.INTERVIEW_ANSWER_SAVE_FAILED,
                    "保存答案失败，请稍后重试");
        }
    }

    /**
     * 将评估任务入队（Redis Stream）。
     * <p>
     * 设置评估状态为 PENDING 后通过 Stream 发送任务消息。
     * 消费者（评估监听器）收到消息后异步执行 LLM 评估。
     * <p>
     * 为什么用 Redis Stream 而非直接 @Async？
     * Stream 提供持久化的任务队列：如果应用重启，队列中的任务不会丢失。
     * @Async 的任务在内存中，进程崩溃即丢失。
     */
    private void enqueueEvaluationTask(String sessionId) {
        persistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        evaluateStreamProducer.sendEvaluateTask(sessionId);
        log.info("会话 {} 已完成所有问题，评估任务已入队", sessionId);
    }

    /**
     * 暂存答案。不进入下一题，不改变 currentIndex。
     * <p>
     * 与 submitAnswer 的核心区别：
     * <table>
     *   <tr><td>submitAnswer</td><td>保存答案 → currentIndex++ → 可能完成面试</td></tr>
     *   <tr><td>saveAnswer</td><td>保存答案 → 不移动 currentIndex</td></tr>
     * </table>
     * 适用场景：用户写了答案但还想修改，先保存草稿。
     */
    public void saveAnswer(SubmitAnswerRequest request) {
        CachedSession session = getOrRestoreSession(request.sessionId());
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        int index = request.questionIndex();
        if (index < 0 || index >= questions.size()) {
            throw new BusinessException(ErrorCode.INTERVIEW_QUESTION_NOT_FOUND, "无效的问题索引: " + index);
        }

        // 更新问题答案
        InterviewQuestionDTO question = questions.get(index);
        InterviewQuestionDTO answeredQuestion = question.withAnswer(request.answer());
        questions.set(index, answeredQuestion);

        // 更新 Redis 缓存
        sessionCache.updateQuestions(request.sessionId(), questions);

        // 更新状态为进行中
        if (session.getStatus() == SessionStatus.CREATED) {
            sessionCache.updateSessionStatus(request.sessionId(), SessionStatus.IN_PROGRESS);
        }

        // 保存答案到数据库（不更新currentIndex）
        try {
            persistenceService.saveAnswer(
                    request.sessionId(), index,
                    question.question(), question.category(),
                    request.answer(), 0, null
            );
            persistenceService.updateSessionStatus(request.sessionId(),
                    InterviewSessionEntity.SessionStatus.IN_PROGRESS);
        } catch (Exception e) {
            log.warn("暂存答案到数据库失败: {}", e.getMessage());
        }

        log.info("会话 {} 暂存答案: 问题{}", request.sessionId(), index);
    }

    /**
     * 在未回答完所有问题时主动结束面试（提前交卷）。
     * <p>
     * 交卷后触发异步评估，未回答的题目在报告中标记为"未回答"（0 分）。
     * <p>
     * 已完成的面试不允许再次交卷，会抛出 INTERVIEW_ALREADY_COMPLETED。
     */
    public void completeInterview(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);

        if (session.getStatus() == SessionStatus.COMPLETED || session.getStatus() == SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED);
        }

        // 更新 Redis 缓存
        sessionCache.updateSessionStatus(sessionId, SessionStatus.COMPLETED);

        // 更新数据库状态
        try {
            persistenceService.updateSessionStatus(sessionId,
                    InterviewSessionEntity.SessionStatus.COMPLETED);
            // 设置评估状态为 PENDING
            persistenceService.updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        } catch (Exception e) {
            log.warn("更新会话状态失败: {}", e.getMessage());
        }

        // 发送评估任务到 Redis Stream
        evaluateStreamProducer.sendEvaluateTask(sessionId);

        log.info("会话 {} 提前交卷，评估任务已入队", sessionId);
    }

    /**
     * 获取或恢复会话（Read-Through 模式）。
     * <p>
     * 优先从 Redis 获取，同时刷新 TTL（滑动过期）。失败则从 DB 恢复，并回填到 Redis 缓存。
     * <p>
     * 没有调用此方法直接操作 Redis 的代码路径——所有会话操作都经过此方法，
     * 保证了缓存策略的一致性。
     */
    private CachedSession getOrRestoreSession(String sessionId) {
        // 1. 尝试从 Redis 缓存获取
        Optional<CachedSession> cachedOpt = sessionCache.getSession(sessionId);
        if (cachedOpt.isPresent()) {
            // 刷新 TTL
            sessionCache.refreshSessionTTL(sessionId);
            return cachedOpt.get();
        }

        // 2. 缓存未命中，从数据库恢复
        CachedSession restoredSession = restoreSessionFromDatabase(sessionId);
        if (restoredSession == null) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        return restoredSession;
    }

    /**
     * 同步生成并获取面试评估报告。
     * <p>
     * 如果会话状态为 COMPLETED 或 EVALUATED，执行评估：
     * <ol>
     *   <li>获取 LLM 客户端（优先使用创建会话时的 provider）</li>
     *   <li>调用评估服务生成报告（可能耗时 30-60s）</li>
     *   <li>更新 Redis 状态为 EVALUATED</li>
     *   <li>将报告持久化到 DB</li>
     * </ol>
     * <p>
     * <b>注意</b>：此方法是同步的，HTTP 请求会阻塞直到评估完成。
     * 生产环境中建议改为异步模式（前端轮询报告就绪状态），此方法作为"同步兜底"保留（当异步评估失败时手动触发）。
     *
     * @param sessionId 会话 ID
     * @return 评估报告 DTO
     */
    public InterviewReportDTO generateReport(String sessionId) {
        CachedSession session = getOrRestoreSession(sessionId);

        // 面试已生成评估报告仍然往下执行，因为报告的保存逻辑是更新而非覆盖。
        if (session.getStatus() != SessionStatus.COMPLETED && session.getStatus() != SessionStatus.EVALUATED) {
            throw new BusinessException(ErrorCode.INTERVIEW_NOT_COMPLETED, "面试尚未完成，无法生成报告");
        }

        log.info("生成面试报告: {}", sessionId);

        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);

        // 获取 LLM 客户端
        String provider = null;
        Optional<InterviewSessionEntity> entityOpt = persistenceService.findBySessionId(sessionId);
        if (entityOpt.isPresent()) {
            provider = entityOpt.get().getLlmProvider();
        }
        ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(provider);

        InterviewReportDTO report = evaluationService.evaluateInterview(
                chatClient,
                sessionId,
                session.getResumeText(),
                questions
        );

        // 更新 Redis 缓存状态
        sessionCache.updateSessionStatus(sessionId, SessionStatus.EVALUATED);

        // 保存报告到数据库
        try {
            persistenceService.saveReport(sessionId, report);
        } catch (Exception e) {
            log.warn("保存报告到数据库失败: {}", e.getMessage());
        }

        return report;
    }

    /**
     * 将 CachedSession 转换为 InterviewSessionDTO。
     * <p>
     * 转换过程中会反序列化 questionsJson（调用 getQuestions），
     * 这是唯一能正确构造 DTO 中 questions 列表的方式。
     */
    private InterviewSessionDTO toDTO(CachedSession session) {
        List<InterviewQuestionDTO> questions = session.getQuestions(objectMapper);
        return new InterviewSessionDTO(
                session.getSessionId(),
                session.getResumeText(),
                questions.size(),
                session.getCurrentIndex(),
                questions,
                session.getStatus(),
                session.getKnowledgeBaseId(),
                session.getInterviewCategory()
        );
    }
}

