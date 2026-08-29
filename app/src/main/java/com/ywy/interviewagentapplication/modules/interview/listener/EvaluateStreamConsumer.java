package com.ywy.interviewagentapplication.modules.interview.listener;

import com.ywy.interviewagentapplication.common.async.AbstractStreamConsumer;
import com.ywy.interviewagentapplication.common.ai.LlmProviderRegistry;
import com.ywy.interviewagentapplication.common.constant.AsyncTaskStreamConstants;
import com.ywy.interviewagentapplication.common.model.AsyncTaskStatus;
import com.ywy.interviewagentapplication.infrastructure.redis.RedisService;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewAnswerEntity;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewQuestionDTO;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewReportDTO;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewSessionEntity;
import com.ywy.interviewagentapplication.modules.interview.repository.InterviewSessionRepository;
import com.ywy.interviewagentapplication.modules.interview.service.AnswerEvaluationService;
import com.ywy.interviewagentapplication.modules.interview.service.InterviewPersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 面试评估 Stream 消费者：从 Redis Stream 拉取评估任务并执行。
 *
 * <h3>消费者生命周期方法（模板方法模式）</h3>
 * 父类 AbstractStreamConsumer 定义了消费的主循环，子类实现这些回调：
 * <ol>
 *   <li>{@link #shouldSkip}：消息过滤，已完成评估的任务跳过</li>
 *   <li>{@link #markProcessing}：更新状态为 PROCESSING</li>
 *   <li>{@link #processBusiness}：执行实际评估（核心逻辑）</li>
 *   <li>{@link #markCompleted}：更新状态为 COMPLETED</li>
 *   <li>{@link #markFailed}：更新状态为 FAILED + 错误信息</li>
 *   <li>{@link #retryMessage}：失败重试，重新发送消息到 Stream</li>
 * </ol>
 *
 * <h3>shouldSkip 的双重保护</h3>
 * 消息可能因以下原因被重复投递：
 * <ul>
 *   <li>消费者崩溃后 Pending 消息被回收</li>
 *   <li>网络重试导致消息重复发送</li>
 * </ul>
 * {@code shouldSkip} 检查数据库中的评估状态：如果已经是 COMPLETED，
 * 跳过本次消费。这是"至少一次"投递语义下的幂等性保护。
 * <p>
 * 如果会话已被删除（findBySessionId 返回 empty），orElse(true) 也返回 true
 * 跳过，避免消费一个已不存在的会话。
 *
 * <h3>失败重试策略</h3>
 * 评估失败后，通过 {@link #retryMessage} 将消息重新发送到 Stream 尾部
 * （retryCount + 1）。重试次数由父类 AbstractStreamConsumer 控制。
 * 超过最大重试次数后调用 {@link #markFailed}，不再重试。
 *
 * @see EvaluateStreamProducer 对应的生产者
 * @see AbstractStreamConsumer 父类模板
 */
@Slf4j
@Component
public class EvaluateStreamConsumer extends AbstractStreamConsumer<EvaluateStreamConsumer.EvaluatePayload> {

    private final InterviewSessionRepository sessionRepository;
    private final AnswerEvaluationService evaluationService;
    private final InterviewPersistenceService persistenceService;
    private final ObjectMapper objectMapper;
    private final LlmProviderRegistry llmProviderRegistry;

    public EvaluateStreamConsumer(
            RedisService redisService,
            InterviewSessionRepository sessionRepository,
            AnswerEvaluationService evaluationService,
            InterviewPersistenceService persistenceService,
            ObjectMapper objectMapper,
            LlmProviderRegistry llmProviderRegistry
    ) {
        super(redisService);
        this.sessionRepository = sessionRepository;
        this.evaluationService = evaluationService;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
        this.llmProviderRegistry = llmProviderRegistry;
    }

    /** 评估任务的负载数据：sessionId */
    record EvaluatePayload(String sessionId) {}

    @Override
    protected String taskDisplayName() {
        return "评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "evaluate-consumer";
    }

    /**
     * 解析消息体为 EvaluatePayload。
     * <p>
     * 从消息的 Map 中提取 sessionId 字段。
     * 如果消息格式错误（缺少 sessionId），返回 null，父类会跳过该消息并记录日志。
     *
     * @param messageId Stream 消息ID
     * @param data      消息内容
     * @return EvaluatePayload，格式错误时返回 null
     */
    @Override
    protected EvaluatePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String sessionId = data.get(AsyncTaskStreamConstants.FIELD_SESSION_ID);
        if (sessionId == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new EvaluatePayload(sessionId);
    }

    @Override
    protected String payloadIdentifier(EvaluatePayload payload) {
        return "sessionId=" + payload.sessionId();
    }

    /**
     * 检查是否应跳过此消息（幂等性保护）。
     * <p>
     * 如果评估已完成（COMPLETED）或会话已不存在，跳过。
     */
    @Override
    protected boolean shouldSkip(EvaluatePayload payload) {
        return sessionRepository.findBySessionId(payload.sessionId())
                .map(session -> session.getEvaluateStatus() == AsyncTaskStatus.COMPLETED)
                .orElse(true);
    }

    @Override
    protected void markProcessing(EvaluatePayload payload) {
        updateEvaluateStatus(payload.sessionId(), AsyncTaskStatus.PROCESSING, null);
    }

    /**
     * 执行实际的评估逻辑（核心方法）。
     * <p>
     * <b>处理步骤</b>：
     * <ol>
     *   <li>从数据库加载会话（含简历文本）</li>
     *   <li>从 questionsJson 反序列化问题列表</li>
     *   <li>从答案表加载答案</li>
     *   <li>获取 LLM 客户端（优先使用创建会话时的 provider）</li>
     *   <li>调用评估服务生成报告</li>
     *   <li>保存报告到数据库</li>
     * </ol>
     * <p>
     * <b>为什么从数据库加载而非从 Redis 缓存？</b>
     * 评估是异步的，从入队到消费可能间隔数分钟甚至数小时。
     * Redis 缓存 TTL 24h，但评估数据需要绝对准确（不能因缓存不一致导致报告内容出错）。数据库是唯一可靠的数据源。
     * <p>
     * <b>会话已被删除的处理</b>：
     * 如果会话在入队后、消费前被删除，直接返回（不抛异常）。这是正常情况，用户可能在等待评估期间删除了面试记录。
     */
    @Override
    protected void processBusiness(EvaluatePayload payload) {
        String sessionId = payload.sessionId();
        Optional<InterviewSessionEntity> sessionOpt = sessionRepository.findBySessionIdWithResume(sessionId);
        if (sessionOpt.isEmpty()) {
            log.warn("会话已被删除，跳过评估任务: sessionId={}", sessionId);
            return;
        }

        InterviewSessionEntity session = sessionOpt.get();
        List<InterviewQuestionDTO> questions = objectMapper.readValue(
                session.getQuestionsJson(),
                new TypeReference<>() {}
        );

        List<InterviewAnswerEntity> answers = persistenceService.findAnswersBySessionId(sessionId);
        for (InterviewAnswerEntity answer : answers) {
            int index = answer.getQuestionIndex();
            if (index >= 0 && index < questions.size()) {
                InterviewQuestionDTO question = questions.get(index);
                questions.set(index, question.withAnswer(answer.getUserAnswer()));
            }
        }

        // 获取 LLM 客户端
        String provider = session.getLlmProvider();
        ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(provider);

        String resumeText = session.getResume() != null ? session.getResume().getResumeText() : "";
        InterviewReportDTO report = evaluationService.evaluateInterview(chatClient, sessionId, resumeText, questions);
        persistenceService.saveReport(sessionId, report);
    }

    @Override
    protected void markCompleted(EvaluatePayload payload) {
        updateEvaluateStatus(payload.sessionId(), AsyncTaskStatus.COMPLETED, null);
    }

    @Override
    protected void markFailed(EvaluatePayload payload, String error) {
        updateEvaluateStatus(payload.sessionId(), AsyncTaskStatus.FAILED, error);
    }

    /**
     * 重试失败的消息：重新入队到 Stream 尾部。
     * <p>
     * 重试次数 retryCount 递增后放入消息体，消费者可据此判断是否超过最大重试次数。
     * 如果重新入队也失败，直接将状态标记为 FAILED（最后兜底）。
     */
    @Override
    protected void retryMessage(EvaluatePayload payload, int retryCount) {
        String sessionId = payload.sessionId();
        try {
            Map<String, String> message = Map.of(
                    AsyncTaskStreamConstants.FIELD_SESSION_ID, sessionId,
                    AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
            );

            redisService().streamAdd(
                    AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY,
                    message,
                    AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("评估任务已重新入队: sessionId={}, retryCount={}", sessionId, retryCount);

        } catch (Exception e) {
            log.error("重试入队失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            updateEvaluateStatus(sessionId, AsyncTaskStatus.FAILED, truncateError("重试入队失败: " + e.getMessage()));
        }
    }

    /**
     * 更新评估状态到数据库。
     * <p>
     * 更新失败时只记录日志不抛异常。
     * 状态更新是辅助性的，不应因为状态记录失败而导致消费过程报错。
     */
    private void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
        try {
            sessionRepository.findBySessionId(sessionId).ifPresent(session -> {
                session.setEvaluateStatus(status);
                session.setEvaluateError(error);
                sessionRepository.save(session);
                log.debug("评估状态已更新: sessionId={}, status={}", sessionId, status);
            });
        } catch (Exception e) {
            log.error("更新评估状态失败: sessionId={}, status={}, error={}", sessionId, status, e.getMessage(), e);
        }
    }

}

