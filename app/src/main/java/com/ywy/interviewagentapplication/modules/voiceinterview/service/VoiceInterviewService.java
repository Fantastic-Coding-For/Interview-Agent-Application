package com.ywy.interviewagentapplication.modules.voiceinterview.service;

import com.ywy.interviewagentapplication.common.ai.LlmProviderRegistry;
import com.ywy.interviewagentapplication.common.constant.CommonConstants.InterviewDefaults;
import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.common.model.AsyncTaskStatus;
import com.ywy.interviewagentapplication.modules.voiceinterview.config.VoiceInterviewProperties;
import com.ywy.interviewagentapplication.modules.voiceinterview.dto.CreateSessionRequest;
import com.ywy.interviewagentapplication.modules.voiceinterview.dto.VoiceInterviewMessageDTO;
import com.ywy.interviewagentapplication.modules.voiceinterview.dto.SessionMetaDTO;
import com.ywy.interviewagentapplication.modules.voiceinterview.dto.SessionResponseDTO;
import com.ywy.interviewagentapplication.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import com.ywy.interviewagentapplication.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import com.ywy.interviewagentapplication.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import com.ywy.interviewagentapplication.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import com.ywy.interviewagentapplication.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import com.ywy.interviewagentapplication.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import com.ywy.interviewagentapplication.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 语音面试服务。
 *
 * <p>职责划分（与 WebSocketHandler / 各云服务解耦的边界）：
 * <ul>
 *   <li><b>会话生命周期</b>：创建、结束（含断连兜底）、暂停/恢复、删除</li>
 *   <li><b>阶段管理</b>：首阶段判定、阶段切换、下一阶段推导（考虑开关组合）</li>
 *   <li><b>消息持久化</b>：一问一答两阶段写入（先 AI 提问、后回填用户回答）、序号生成</li>
 *   <li><b>上下文摘要</b>：SUMMARY 行的读取与原地 UPSERT（悲观锁防并发）</li>
 *   <li><b>评估任务</b>：结束会话后投递评估任务（事务提交后投递，见 sendEvaluateTaskAfterCommit）</li>
 *   <li><b>Redis 缓存</b>：会话实体 1 小时 TTL 缓存，变更即失效</li>
 *   <li><b>定时清理</b>：超时 IN_PROGRESS 会话兜底结束、卡住的评估任务重投/失败</li>
 * </ul>
 *
 * <h3>与 WebSocketHandler 的边界</h3>
 * 本类只做「数据库状态机 + 任务投递」；实时音视频管线（STT→LLM→TTS）
 * 全部在 Handler 中流转。Handler 通过本类读写会话与消息，二者不互相侵入。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewService {

    private final VoiceInterviewSessionRepository sessionRepository;
    private final VoiceInterviewMessageRepository messageRepository;
    private final VoiceInterviewEvaluationRepository evaluationRepository;
    private final RedissonClient redissonClient;
    private final VoiceInterviewProperties properties;
    private final VoiceEvaluateStreamProducer voiceEvaluateStreamProducer;
    private final LlmProviderRegistry llmProviderRegistry;

    private static final String SESSION_CACHE_KEY_PREFIX = "voice:interview:session:";
    /** 会话缓存 TTL：1 小时。热会话高频读写，1 小时后自然过期兜底防脏数据 */
    private static final int CACHE_TTL_HOURS = 1;
    /** 单用户部署阶段的默认用户 ID（多用户上线时替换为登录态） */
    private static final String DEFAULT_USER_ID = "default";
    /** PENDING 评估超过 3 分钟未消费 → 重新投递（消费端可能刚重启丢了消息） */
    private static final Duration PENDING_EVALUATION_REQUEUE_DELAY = Duration.ofMinutes(3);
    /** PROCESSING 评估超过 30 分钟未完成 → 判定卡死，置 FAILED */
    private static final Duration PROCESSING_EVALUATION_TIMEOUT = Duration.ofMinutes(30);

    /**
     * 创建新的语音面试会话。
     *
     * <h3>装配逻辑</h3>
     * <ul>
     *   <li>skillId 缺省回落到全局默认模板（InterviewDefaults.SKILL_ID）</li>
     *   <li>roleType 直接复用 skillId</li>
     *   <li>首阶段由四开关决定（determineFirstPhase），后续切换按同样优先级推导</li>
     * </ul>
     *
     * @param request Session creation request with role type and phase configuration
     * @return SessionResponseDTO with session details and WebSocket URL
     */
    @Transactional
    public SessionResponseDTO createSession(CreateSessionRequest request) {
        String effectiveSkillId = request.getSkillId() != null ? request.getSkillId() : InterviewDefaults.SKILL_ID;
        String effectiveLlmProvider = (request.getLlmProvider() != null && !request.getLlmProvider().isBlank())
                ? request.getLlmProvider()
                : null;

        VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
                .userId(DEFAULT_USER_ID)
                .roleType(effectiveSkillId)
                .skillId(effectiveSkillId)
                .difficulty(request.getDifficulty() != null ? request.getDifficulty() : InterviewDefaults.DIFFICULTY)
                .customJdText(request.getCustomJdText())
                .resumeId(request.getResumeId())
                .introEnabled(request.getIntroEnabled())
                .techEnabled(request.getTechEnabled())
                .projectEnabled(request.getProjectEnabled())
                .hrEnabled(request.getHrEnabled())
                .llmProvider(effectiveLlmProvider)
                .plannedDuration(request.getPlannedDuration())
                .currentPhase(determineFirstPhase(request))
                .build();

        VoiceInterviewSessionEntity saved = sessionRepository.save(session);
        cacheSession(saved);

        log.info("Created voice interview session: {} with template: {}, phase: {}",
                saved.getId(), effectiveSkillId, saved.getCurrentPhase());

        return buildSessionResponse(saved);
    }

    /**
     * 仅用于结束 IN_PROGRESS 状态的会话，作为 WebSocket 异常断开的兜底。
     * 正常结束的 endSession 已设为 COMPLETED，调用此方法直接结束。
     *
     * <p>断连兜底的动机：面试中浏览器崩溃/网络断开时没人调 end_interview，
     * 会话会永远停留在 IN_PROGRESS（占用并发额度、评估永不触发）。
     * 断连瞬间就兜底结束，比等 2 小时的定时清理更及时。
     */
    @Transactional
    public void endSessionIfInProgress(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = sessionRepository.findById(sessionIdLong).orElse(null);
        if (session == null || session.getStatus() != VoiceInterviewSessionStatus.IN_PROGRESS) {
            return;
        }
        log.info("Auto-ending IN_PROGRESS session {} after WebSocket disconnect", sessionId);
        endSession(session);
        sendEvaluateTaskAfterCommit(sessionIdLong);
    }

    /**
     * 结束面试会话并更新状态
     *
     * @param sessionId Session ID (String format, will be converted to Long)
     */
    @Transactional
    public void endSession(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = getSession(sessionIdLong);

        if (session == null) {
            log.warn("Session not found: {}", sessionId);
            return;
        }

        endSession(session);
        sendEvaluateTaskAfterCommit(sessionIdLong);
    }

    /**
     * 处理结束会话的公共操作：置终态、算时长、触发评估。
     *
     * <p>评估状态先置 PENDING 再投递任务；消费者完成后回写 COMPLETED/FAILED。
     */
    private void endSession(VoiceInterviewSessionEntity session) {
        session.setEndTime(LocalDateTime.now());
        session.setCurrentPhase(VoiceInterviewSessionEntity.InterviewPhase.COMPLETED);
        session.setStatus(VoiceInterviewSessionStatus.COMPLETED);
        session.setActualDuration((int) Duration.between(session.getStartTime(), LocalDateTime.now()).toSeconds());
        session.setEvaluateStatus(AsyncTaskStatus.PENDING);

        sessionRepository.save(session);
        invalidateSessionCache(session.getId());

        log.info("Ended voice interview session: {}, duration: {} seconds, evaluation triggered",
                session.getId(), session.getActualDuration());
    }

    /**
     * 通过ID获取会话。
     * <p>委托给 getSession(Long sessionId)：先查 Redis 缓存，失败再查数据库</p>
     *
     * @param sessionId Session ID (String format, will be converted to Long)
     * @return VoiceInterviewSessionEntity or null if not found
     */
    public VoiceInterviewSessionEntity getSession(String sessionId) {
        return getSession(parseSessionId(sessionId));
    }

    /**
     * 通过ID获取会话，先查 Redis 缓存，失败再查数据库。
     *
     * <h3>缓存策略</h3>
     * 查库命中<b>不回填缓存</b>——
     * 只有写入路径（create/resume/startPhase）会主动 cacheSession。
     * 这种「写时缓存」策略保证缓存里永远是「最近被修改过的版本」，
     * 避免 read-through 回填引入的过期风险。
     *
     * @param sessionId Session ID as Long
     * @return VoiceInterviewSessionEntity or null if not found
     */
    public VoiceInterviewSessionEntity getSession(Long sessionId) {
        if (sessionId == null) {
            return null;
        }

        // Try cache first
        String cacheKey = getSessionCacheKey(sessionId);
        RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
        VoiceInterviewSessionEntity cached = bucket.get();

        if (cached != null) {
            log.debug("Session {} found in cache", sessionId);
            return cached;
        }

        // Fallback to database
        return sessionRepository.findById(sessionId).orElse(null);
    }

    /**
     * 开始新的面试阶段。
     *
     * <p>非法阶段字符串只记日志不抛异常：前端传错阶段不应打断面试流程，
     * 保持当前阶段继续即可（宽容输入原则）。
     *
     * @param sessionId Session ID (String format)
     * @param phaseStr  Phase as string (INTRO, TECH, PROJECT, HR)
     */
    @Transactional
    public void startPhase(String sessionId, String phaseStr) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = getSession(sessionIdLong);

        if (session == null) {
            log.warn("Cannot start phase - session not found: {}", sessionId);
            return;
        }

        try {
            VoiceInterviewSessionEntity.InterviewPhase newPhase =
                    VoiceInterviewSessionEntity.InterviewPhase.valueOf(phaseStr.toUpperCase());

            VoiceInterviewSessionEntity.InterviewPhase oldPhase = session.getCurrentPhase();
            session.setCurrentPhase(newPhase);
            sessionRepository.save(session);
            cacheSession(session); // Update cache

            log.info("Session {} transitioned from phase {} to {}", sessionId, oldPhase, newPhase);

        } catch (IllegalArgumentException e) {
            log.error("Invalid phase string: {}", phaseStr, e);
        }
    }

    /**
     * 获取会话当前阶段
     *
     * @param sessionId Session ID (String format)
     * @return Current InterviewPhase or null if session not found
     */
    public VoiceInterviewSessionEntity.InterviewPhase getCurrentPhase(String sessionId) {
        VoiceInterviewSessionEntity session = getSession(sessionId);
        return session != null ? session.getCurrentPhase() : null;
    }

    /**
     * 保存对话消息（用户和AI文本）到数据库。
     *
     * <h3>两阶段写入的落库逻辑</h3>
     * <ol>
     *   <li>若传入用户文本：先尝试<b>回填</b>最近一条未回答的 AI 提问行
     *       （fillLatestUnansweredQuestion）——回填成功则该文本不再作为新行</li>
     *   <li>若传入 AI 文本：新建一行（AI 提问），等待未来回填</li>
     *   <li>只有用户文本、没有可回填行且无 AI 文本时：直接返回（不产生孤立行）</li>
     * </ol>
     * 这保证了「一问一答一行」的不变式（见 VoiceInterviewMessageEntity 类注释）。
     *
     * @param sessionId Session ID (String format)
     * @param userText  User's recognized speech text
     * @param aiText    AI's generated response text
     */
    @Transactional
    public void saveMessage(String sessionId, String userText, String aiText) {
        Long sessionIdLong = parseSessionId(sessionId);
        VoiceInterviewSessionEntity session = getSession(sessionIdLong);

        if (session == null) {
            log.warn("Cannot save message - session not found: {}", sessionId);
            return;
        }

        String normalizedUserText = VoiceInterviewMessageEntity.trimToNull(userText);
        String normalizedAiText = VoiceInterviewMessageEntity.trimToNull(aiText);

        boolean answerAttached = normalizedUserText != null
                && fillLatestUnansweredQuestion(sessionIdLong, normalizedUserText);
        if (normalizedAiText == null) {
            return;
        }

        VoiceInterviewMessageEntity message = VoiceInterviewMessageEntity.builder()
                .sessionId(sessionIdLong)
                .messageType("DIALOGUE")
                .phase(session.getCurrentPhase())
                .userRecognizedText(normalizedUserText != null && !answerAttached
                        ? normalizedUserText
                        : null)
                .aiGeneratedText(normalizedAiText)
                .sequenceNum(getNextSequenceNum(sessionIdLong))
                .build();

        messageRepository.save(message);
        log.debug("Saved message for session: {}, phase: {}, sequence: {}",
                sessionId, session.getCurrentPhase(), message.getSequenceNum());
    }

    /**
     * 回填最近一条「AI 已提问但用户尚未回答」的消息。
     *
     * <p>为什么按「最近一条」回填：语音面试是严格一问一答的串行对话，
     * 上一轮 AI 提问后用户才开口，最近未回答行一定是本次回答的配对对象。
     *
     * @return 是否回填成功（失败说明没有未回答的提问行）
     */
    private boolean fillLatestUnansweredQuestion(Long sessionId, String userText) {
        return messageRepository
                .findFirstBySessionIdAndUserRecognizedTextIsNullAndAiGeneratedTextIsNotNullOrderBySequenceNumDesc(
                        sessionId)
                .map(message -> {
                    message.setUserRecognizedText(userText);
                    messageRepository.save(message);
                    log.debug("Filled answer for voice message: sessionId={}, sequence={}",
                            sessionId, message.getSequenceNum());
                    return true;
                })
                .orElse(false);
    }

    /**
     * Get conversation history for a session
     * 获取会话的对话历史记录（不是获取摘要，message_type≠SUMMARY）。
     *
     * @param sessionId Session ID (String format)
     * @return List of messages ordered by sequence number
     */
    public List<VoiceInterviewMessageEntity> getConversationHistory(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        return messageRepository.findBySessionIdAndMessageTypeNotOrderBySequenceNumAsc(
                sessionIdLong, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY);
    }

    /**
     * 读取会话已持久化的上下文摘要行（message_type=SUMMARY）。无则空 Optional。
     */
    public Optional<VoiceInterviewMessageEntity> loadSummaryRow(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);
        return messageRepository.findFirstBySessionIdAndMessageTypeOrderBySequenceNumAsc(
                sessionIdLong, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY);
    }

    /**
     * 持久化会话的上下文摘要行。在会话行悲观锁内原地更新或创建 SUMMARY 行，避免并发重复。
     * sequenceNum 取 {@code -(coveredTurns + 1)}：负值保证排序最前，且编码已覆盖轮次数。
     *
     * <h3>为什么锁会话行而不是消息行</h3>
     * SUMMARY 行可能尚不存在（首次压缩），无从对「不存在的行」加行锁；
     * 锁会话行（findByIdForUpdate）使同一会话的摘要写入串行化——
     * 并发压缩任务（理论上少见）最多一个生效，另一个排队后读到最新值。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveSummaryRow(String sessionId, String summary, int coveredTurns) {
        Long sessionIdLong = parseSessionId(sessionId);
        sessionRepository.findByIdForUpdate(sessionIdLong)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));
        VoiceInterviewMessageEntity row = messageRepository
                .findFirstBySessionIdAndMessageTypeOrderBySequenceNumAsc(
                        sessionIdLong, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY)
                .orElseGet(() -> VoiceInterviewMessageEntity.builder()
                        .sessionId(sessionIdLong)
                        .messageType(VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY)
                        .build());
        row.setAiGeneratedText(summary);
        row.setSequenceNum(-(coveredTurns + 1));
        messageRepository.save(row);
    }

    /**
     * 会话历史转为语音面试消息 DTO 列表。
     */
    public List<VoiceInterviewMessageDTO> getConversationHistoryDTO(String sessionId) {
        return getConversationHistory(sessionId).stream()
                .map(msg -> VoiceInterviewMessageDTO.builder()
                        .id(msg.getId())
                        .sessionId(msg.getSessionId())
                        .messageType(msg.getMessageType())
                        .phase(msg.getPhase() != null ? msg.getPhase().name() : null)
                        .userRecognizedText(msg.getUserRecognizedText())
                        .aiGeneratedText(msg.getAiGeneratedText())
                        .timestamp(msg.getTimestamp())
                        .sequenceNum(msg.getSequenceNum())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 暂停面试会话（用户主动暂停或超时自动暂停）。
     *
     * <p>状态守卫：仅 IN_PROGRESS 可暂停，重复暂停/对已结束会话暂停被拒绝（BusinessException），保证状态机的单向合法性。
     *
     * @param sessionId Session ID
     * @param reason Pause reason (user_initiated or timeout)
     */
    @Transactional
    public void pauseSession(String sessionId, String reason) {
        Long sessionIdLong = parseSessionId(sessionId);

        VoiceInterviewSessionEntity session = sessionRepository.findById(sessionIdLong)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));

        if (session.getStatus() != VoiceInterviewSessionStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "会话状态为 " + session.getStatus() + "，无法暂停"
            );
        }

        session.setStatus(VoiceInterviewSessionStatus.PAUSED);
        session.setPausedAt(LocalDateTime.now());

        sessionRepository.save(session);
        invalidateSessionCache(sessionIdLong);

        log.info("Session {} paused, reason: {}", sessionId, reason);
    }

    /**
     * 恢复面试会话。
     *
     * <p>恢复后返回新的 SessionResponseDTO（含 WebSocket URL），
     * 前端重新建连即可继续，历史消息都在库里，恢复后开场逻辑会跳过（有历史开场白）。
     *
     * @param sessionId Session ID
     * @return SessionResponseDTO with WebSocket URL
     */
    @Transactional
    public SessionResponseDTO resumeSession(String sessionId) {
        Long sessionIdLong = parseSessionId(sessionId);

        VoiceInterviewSessionEntity session = sessionRepository.findById(sessionIdLong)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId));

        if (session.getStatus() != VoiceInterviewSessionStatus.PAUSED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "会话状态为 " + session.getStatus() + "，无法恢复"
            );
        }

        session.setStatus(VoiceInterviewSessionStatus.IN_PROGRESS);
        session.setResumedAt(LocalDateTime.now());

        VoiceInterviewSessionEntity saved = sessionRepository.save(session);
        cacheSession(saved);

        log.info("Session {} resumed with {} messages in conversation history",
                sessionId, countDialogueMessages(sessionIdLong));

        return buildSessionResponse(saved);
    }

    /**
     * 获取用户所有会话（列表页）。
     *
     * <p>状态过滤入参是 String：非法值会抛 IllegalArgumentException（valueOf），由全局异常处理器处理。
     *
     * @param userId User ID (optional, defaults to DEFAULT_USER_ID)
     * @param status Filter by status (optional)
     * @return List of session metadata
     */
    public List<SessionMetaDTO> getAllSessions(String userId, String status) {
        userId = userId != null ? userId : DEFAULT_USER_ID;

        List<VoiceInterviewSessionEntity> sessions;
        if (status != null && !status.isEmpty()) {
            VoiceInterviewSessionStatus statusEnum =
                    VoiceInterviewSessionStatus.valueOf(status.toUpperCase());
            sessions = sessionRepository.findByUserIdAndStatusOrderByUpdatedAtDesc(userId, statusEnum);
        } else {
            sessions = sessionRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        }

        return sessions.stream()
                .map(session -> SessionMetaDTO.builder()
                        .sessionId(session.getId())
                        .roleType(session.getRoleType())
                        .status(session.getStatus().name())
                        .currentPhase(session.getCurrentPhase().name())
                        .createdAt(session.getCreatedAt())
                        .updatedAt(session.getUpdatedAt())
                        .actualDuration(session.getActualDuration())
                        .messageCount(countDialogueMessages(session.getId()))
                        .evaluateStatus(session.getEvaluateStatus() != null ? session.getEvaluateStatus().name() : null)
                        .evaluateError(session.getEvaluateError())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 通过ID获取会话响应DTO
     *
     * @param sessionId Session ID as Long
     * @return SessionResponseDTO with session details or null if not found
     */
    public SessionResponseDTO getSessionDTO(Long sessionId) {
        VoiceInterviewSessionEntity session = getSession(sessionId);

        if (session == null) {
            return null;
        }

        return buildSessionResponse(session);
    }

    /**
     * 检查是否应该转换到下一个阶段（基于时长和问题数量）。
     *
     * <h3>三条切换规则（或关系）</h3>
     * <ol>
     *   <li>达到该阶段最大时长 → 强制切换（防无限拖延）</li>
     *   <li>问满最大题数 → 建议切换（信息量已足够）</li>
     *   <li>达到建议时长 且 问满最少题数 → 建议切换（质量与节奏的平衡点）</li>
     * </ol>
     * 通过简单启发式代替 AI 判断
     * @param session        Current session
     * @param phaseStartTime Time when current phase started
     * @param questionCount  Number of questions asked in current phase
     * @return true if phase should transition, false otherwise
     */
    public boolean shouldTransitionToNextPhase(VoiceInterviewSessionEntity session,
                                               LocalDateTime phaseStartTime,
                                               int questionCount) {
        VoiceInterviewSessionEntity.InterviewPhase currentPhase = session.getCurrentPhase();
        if (currentPhase == null || currentPhase == VoiceInterviewSessionEntity.InterviewPhase.COMPLETED) {
            return false;
        }

        Duration phaseDuration = Duration.between(phaseStartTime, LocalDateTime.now());
        VoiceInterviewProperties.DurationConfig config = getPhaseConfig(currentPhase);

        // Rule 1: Max duration reached (forced transition)
        if (phaseDuration.toMinutes() >= config.getMaxDuration()) {
            log.info("Phase {} reached max duration {} minutes, forcing transition",
                    currentPhase, config.getMaxDuration());
            return true;
        }

        // Rule 2: Min questions reached and sufficient information gathered (AI judgment)
        // For MVP, we use a simple heuristic based on question count
        if (questionCount >= config.getMaxQuestions()) {
            log.info("Phase {} reached max questions {}, suggesting transition",
                    currentPhase, config.getMaxQuestions());
            return true;
        }

        // Rule 3: Suggested duration reached with min questions
        if (phaseDuration.toMinutes() >= config.getSuggestedDuration()
                && questionCount >= config.getMinQuestions()) {
            log.info("Phase {} reached suggested duration {} with {} questions, suggesting transition",
                    currentPhase, config.getSuggestedDuration(), questionCount);
            return true;
        }

        return false;
    }

    /**
     * 获取当前阶段之后的下一个启用的阶段。
     *
     * <h3>跳过的语义</h3>
     * 用 switch 的三元链实现「跳过未启用阶段」：
     * 如 TECH 之后若 projectEnabled=false 且 hrEnabled=true，
     * 则下一阶段直接是 HR（PROJECT 被跳过）。全未启用则 COMPLETED。
     *
     * @param session Current session
     * @return Next InterviewPhase or COMPLETED if no more phases
     */
    public VoiceInterviewSessionEntity.InterviewPhase getNextPhase(VoiceInterviewSessionEntity session) {
        VoiceInterviewSessionEntity.InterviewPhase current = session.getCurrentPhase();
        if (current == null) {
            return getFirstEnabledPhase(session);
        }

        return switch (current) {
            case INTRO -> session.getTechEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.TECH :
                    session.getProjectEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.PROJECT :
                            session.getHrEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.HR :
                                    VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
            case TECH -> session.getProjectEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.PROJECT :
                    session.getHrEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.HR :
                            VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
            case PROJECT -> session.getHrEnabled() ? VoiceInterviewSessionEntity.InterviewPhase.HR :
                    VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
            case HR, COMPLETED -> VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
        };
    }

    // ==================== Private Helper Methods ====================

    /**
     * 从会话创建请求获取第一个启用的阶段：按 INTRO → TECH → PROJECT → HR 的顺序；全关则直接 COMPLETED（创建即结束的边界场景）。
     */
    private VoiceInterviewSessionEntity.InterviewPhase determineFirstPhase(CreateSessionRequest request) {
        if (request.getIntroEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.INTRO;
        if (request.getTechEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.TECH;
        if (request.getProjectEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.PROJECT;
        if (request.getHrEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.HR;
        return VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
    }

    /**
     * 从会话实体获取第一个启用的阶段（与 determineFirstPhase 同规则，仅入参不同）。
     */
    private VoiceInterviewSessionEntity.InterviewPhase getFirstEnabledPhase(VoiceInterviewSessionEntity session) {
        if (session.getIntroEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.INTRO;
        if (session.getTechEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.TECH;
        if (session.getProjectEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.PROJECT;
        if (session.getHrEnabled()) return VoiceInterviewSessionEntity.InterviewPhase.HR;
        return VoiceInterviewSessionEntity.InterviewPhase.COMPLETED;
    }

    /**
     * 组装会话响应 DTO。
     *
     * <p><b>注意</b>：webSocketUrl 硬编码 localhost:8080 仅适用于本地开发；
     * 部署到真实环境需改为按请求域名/协议（wss）动态拼接。
     */
    private SessionResponseDTO buildSessionResponse(VoiceInterviewSessionEntity session) {
        return SessionResponseDTO.builder()
                .sessionId(session.getId())
                .roleType(session.getRoleType())
                .currentPhase(session.getCurrentPhase().name())
                .status(session.getStatus().name())
                .startTime(session.getStartTime())
                .plannedDuration(session.getPlannedDuration())
                .webSocketUrl(String.format("ws://localhost:8080/ws/voice-interview/%d", session.getId()))
                .build();
    }

    /**
     * 从配置类读取指定的面试阶段配置；未知阶段返回全零配置（恒不触发切换，防御性兜底）。
     */
    private VoiceInterviewProperties.DurationConfig getPhaseConfig(VoiceInterviewSessionEntity.InterviewPhase phase) {
        return switch (phase) {
            case INTRO -> properties.getPhase().getIntro();
            case TECH -> properties.getPhase().getTech();
            case PROJECT -> properties.getPhase().getProject();
            case HR -> properties.getPhase().getHr();
            default -> new VoiceInterviewProperties.DurationConfig(0, 0, 0, 0, 0);
        };
    }

    /**
     * 下一消息序号 = 现有对话轮次 + 1（排除 SUMMARY 行）。
     *
     * <p>用 count 而非 max(sequenceNum)：SUMMARY 行占负值域，
     * max 取值会受其干扰；count 排除 SUMMARY 后恰好等于对话行数。
     */
    private int getNextSequenceNum(Long sessionId) {
        return (int) countDialogueMessages(sessionId) + 1;
    }

    /**
     * 统计对话轮次数（排除 SUMMARY 摘要行）。
     */
    private long countDialogueMessages(Long sessionId) {
        return messageRepository.countBySessionIdAndMessageTypeNot(
                sessionId, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY);
    }

    /**
     * 更新会话上的评估状态（生产者失败回调 / 消费者 / Controller 三方共用）。
     *
     * <p>实现为「尽力而为」：会话不存在时静默跳过——评估状态是冗余数据，
     * 主业务（面试）不依赖它成功，绝不让评估状态的更新失败影响主流程。
     */
    public void updateEvaluateStatus(Long sessionId, AsyncTaskStatus status, String error) {
        try {
            sessionRepository.findById(sessionId).ifPresent(session -> {
                session.setEvaluateStatus(status);
                session.setEvaluateError(error);
                sessionRepository.save(session);
                invalidateSessionCache(sessionId);
                log.debug("Evaluation status updated: sessionId={}, status={}", sessionId, status);
            });
        } catch (Exception e) {
            log.error("Failed to update evaluation status: sessionId={}, status={}, error={}",
                    sessionId, status, e.getMessage(), e);
        }
    }

    /**
     * 手动触发评估（Controller 入口）：置 PENDING 并在事务提交后投递任务。
     */
    @Transactional
    public void triggerEvaluation(Long sessionId) {
        updateEvaluateStatus(sessionId, AsyncTaskStatus.PENDING, null);
        sendEvaluateTaskAfterCommit(sessionId);
    }

    /**
     * 事务提交后再投递评估任务。
     *
     * <h3>为什么必须 afterCommit</h3>
     * 消费者消费任务后立刻按会话ID读数据库取对话历史——若在事务提交前投递，
     * 消费者可能读到「未提交的旧数据」（隔离性），甚至读到不存在的会话
     * （事务回滚时）。afterCommit 保证「任务可见时，数据必然已落库」，
     * 这是异步任务与事务协调的经典模式。
     */
    private void sendEvaluateTaskAfterCommit(Long sessionId) {
        Runnable sendTask = () -> voiceEvaluateStreamProducer.sendEvaluateTask(sessionId.toString());
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sendTask.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sendTask.run();
            }
        });
    }

    /**
     * 删除语音面试会话及其关联的消息和评估记录。
     *
     * <p>删除顺序：评估 → 消息 → 会话（先子后父，避免外键/孤儿数据）。
     */
    @Transactional
    public void deleteSession(Long sessionId) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在: " + sessionId);
        }
        evaluationRepository.findBySessionId(sessionId).ifPresent(evaluationRepository::delete);
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteById(sessionId);
        log.info("Deleted voice interview session: {}", sessionId);
    }

    /**
     * 将会话写入 redis 缓存（TTL 1 小时）。
     */
    private void cacheSession(VoiceInterviewSessionEntity session) {
        String cacheKey = getSessionCacheKey(session.getId());
        RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
        bucket.set(session, Duration.ofHours(CACHE_TTL_HOURS));
        log.debug("Cached session: {}", session.getId());
    }

    /**
     * 失效会话缓存（会话执行写操作后调用，保证下次读取拿到最新值）。
     */
    private void invalidateSessionCache(Long sessionId) {
        String cacheKey = getSessionCacheKey(sessionId);
        RBucket<VoiceInterviewSessionEntity> bucket = redissonClient.getBucket(cacheKey);
        bucket.delete();
        log.debug("Invalidated cache for session: {}", sessionId);
    }

    /**
     * 生成会话缓存 key（前缀 + 会话ID）。
     */
    private String getSessionCacheKey(Long sessionId) {
        return SESSION_CACHE_KEY_PREFIX + sessionId;
    }

    /**
     * 会话ID字符串转 Long，失败返回 null。
     */
    private Long parseSessionId(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            return Long.parseLong(sessionId);
        } catch (NumberFormatException e) {
            log.error("Invalid session ID format: {}", sessionId, e);
            return null;
        }
    }

    /**
     * 清理超时会话，重新投递卡住的 PENDING 评估，并结束超时的 PROCESSING 评估。
     * 由 @Scheduled 在 WebSocketHandler 中定时触发。
     *
     * <h3>三路扫描（各自独立阈值）</h3>
     * <ol>
     *   <li><b>僵尸会话</b>：IN_PROGRESS 且 startTime 超过 2 小时——
     *       断连兜底漏网的场景（如服务重启丢失内存态），强制结束并触发评估</li>
     *   <li><b>评估 PENDING 卡死</b>：评估消息投递后 3 分钟无人消费（消费者宕机/重启丢失），
     *       重新投递一次；依赖消费者侧的任务幂等保证不重复评估</li>
     *   <li><b>评估 PROCESSING 卡死</b>：评估执行超过 30 分钟（LLM 挂起/消费者崩溃），
     *       置 FAILED 并给出可读错误，让前端不再无限轮询</li>
     * </ol>
     * 阈值选取的权衡：太短误杀正常任务（评估本身要几十秒~几分钟），
     * 太长则异常恢复慢——3 分钟/30 分钟是「容忍正常耗时」与「及时止损」的折中。
     *
     * @return 被清理/重投/失败化的会话总数（供调度日志统计）
     */
    @Transactional
    public int cleanupStaleSessions() {
        LocalDateTime staleThreshold = LocalDateTime.now().minusHours(2);

        List<VoiceInterviewSessionEntity> staleSessions = sessionRepository
                .findByStatusAndStartTimeBefore(VoiceInterviewSessionStatus.IN_PROGRESS, staleThreshold);

        int cleaned = 0;
        for (VoiceInterviewSessionEntity session : staleSessions) {
            log.info("Cleaning up stale IN_PROGRESS session {}, started at {}",
                    session.getId(), session.getStartTime());
            endSession(session);
            sendEvaluateTaskAfterCommit(session.getId());
            cleaned++;
        }

        LocalDateTime pendingStaleThreshold = LocalDateTime.now()
                .minus(PENDING_EVALUATION_REQUEUE_DELAY);
        List<VoiceInterviewSessionEntity> pendingEvals = sessionRepository
                .findByEvaluateStatusAndUpdatedAtBefore(AsyncTaskStatus.PENDING, pendingStaleThreshold);

        for (VoiceInterviewSessionEntity session : pendingEvals) {
            log.warn("Requeueing stale PENDING evaluation for session {}, last updated at {}",
                    session.getId(), session.getUpdatedAt());
            session.setEvaluateError(null);
            // 手动刷新 updatedAt：JPA 的 @PreUpdate 只在实体发生脏变更时触发，
            // 这里显式置时间保证下一次扫描的「最后活跃」基准是本次重投时刻
            session.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(session);
            invalidateSessionCache(session.getId());
            sendEvaluateTaskAfterCommit(session.getId());
            cleaned++;
        }

        LocalDateTime evalStaleThreshold = LocalDateTime.now()
                .minus(PROCESSING_EVALUATION_TIMEOUT);
        List<VoiceInterviewSessionEntity> stuckEvals = sessionRepository
                .findByEvaluateStatusAndUpdatedAtBefore(AsyncTaskStatus.PROCESSING, evalStaleThreshold);

        for (VoiceInterviewSessionEntity session : stuckEvals) {
            log.info("Resetting stuck PROCESSING evaluation for session {}", session.getId());
            session.setEvaluateStatus(AsyncTaskStatus.FAILED);
            session.setEvaluateError("评估超时，请重新触发");
            sessionRepository.save(session);
            invalidateSessionCache(session.getId());
            cleaned++;
        }

        return cleaned;
    }
}

