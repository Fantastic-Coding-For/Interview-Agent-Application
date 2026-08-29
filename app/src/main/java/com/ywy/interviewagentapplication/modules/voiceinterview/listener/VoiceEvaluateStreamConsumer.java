package com.ywy.interviewagentapplication.modules.voiceinterview.listener;

import com.ywy.interviewagentapplication.common.async.AbstractStreamConsumer;
import com.ywy.interviewagentapplication.common.constant.AsyncTaskStreamConstants;
import com.ywy.interviewagentapplication.common.model.AsyncTaskStatus;
import com.ywy.interviewagentapplication.infrastructure.redis.RedisService;
import com.ywy.interviewagentapplication.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import com.ywy.interviewagentapplication.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import com.ywy.interviewagentapplication.modules.voiceinterview.service.VoiceInterviewService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 语音面试评估 Stream 消费者。
 *
 * <p>与生产者（{@code VoiceEvaluateStreamProducer}）配对，消费同一 Redis Stream：
 * 面试结束 → 投递评估任务 → 本消费者异步执行 LLM 评估 → 回写评估状态。
 *
 * <h3>任务状态机（驱动会话表上的 evaluateStatus 冗余字段）</h3>
 * <pre>
 * PENDING →（消费）→ PROCESSING → COMPLETED / FAILED
 *                 ↓ 处理异常
 *              重试入队（retryCount+1）→ 再次 PROCESSING → ...
 * </pre>
 * 状态回写全部通过 {@code VoiceInterviewService#updateEvaluateStatus}，
 * 前端轮询会话接口即可看到评估进度，而不用单独查询评估任务表。
 */
@Slf4j
@Component
public class VoiceEvaluateStreamConsumer extends AbstractStreamConsumer<VoiceEvaluateStreamConsumer.VoiceEvaluatePayload> {

    private final VoiceInterviewService voiceInterviewService;
    private final VoiceInterviewEvaluationService evaluationService;
    private final VoiceInterviewSessionRepository sessionRepository;

    public VoiceEvaluateStreamConsumer(
            RedisService redisService,
            VoiceInterviewService voiceInterviewService,
            VoiceInterviewEvaluationService evaluationService,
            VoiceInterviewSessionRepository sessionRepository
    ) {
        super(redisService);
        this.voiceInterviewService = voiceInterviewService;
        this.evaluationService = evaluationService;
        this.sessionRepository = sessionRepository;
    }

    /**
     * 消息负载：只携带会话ID。
     */
    record VoiceEvaluatePayload(Long sessionId) {}

    @Override
    protected String taskDisplayName() {
        return "语音面试评估";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY;
    }

    /**
     * 消费组名：多实例部署时同组消费者共享消息（每条消息只被一个消费者消费）。
     */
    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_GROUP_NAME;
    }

    /**
     * 消费者实例前缀：同组内区分不同实例（用于 PEL 挂起消息的归属判定）。
     */
    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "voice-evaluate-consumer";
    }

    /**
     * 解析 Stream 消息为负载对象。
     *
     * <p>返回 null 表示「消息格式非法」：父类会直接 ACK 跳过该消息
     * （坏消息不值得重试，重试一万次也解析不出来）。
     */
    @Override
    protected VoiceEvaluatePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String sessionId = data.get(AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID);
        if (sessionId == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new VoiceEvaluatePayload(Long.parseLong(sessionId));
    }

    /**
     * 负载标识（日志/追踪用）。
     */
    @Override
    protected String payloadIdentifier(VoiceEvaluatePayload payload) {
        return "voiceSessionId=" + payload.sessionId();
    }

    /**
     * 幂等短路：评估已完成的消息直接跳过，避免重复评估。
     *
     * <p>会话不存在（orElse(true)）也跳过：删除会话后的迟到消息无评估对象，
     * 执行只会浪费 LLM 调用。这是「投递至少一次 + 消费幂等」组合的典型防御。
     */
    @Override
    protected boolean shouldSkip(VoiceEvaluatePayload payload) {
        return sessionRepository.findById(payload.sessionId())
                .map(session -> session.getEvaluateStatus() == AsyncTaskStatus.COMPLETED)
                .orElse(true);
    }

    /**
     * 开始处理前，状态置 PROCESSING。
     */
    @Override
    protected void markProcessing(VoiceEvaluatePayload payload) {
        voiceInterviewService.updateEvaluateStatus(
                payload.sessionId(), AsyncTaskStatus.PROCESSING, null);
    }

    /**
     * 业务处理：执行 LLM 评估。
     *
     * <p>会话存在性的二次校验：shouldSkip 与 processBusiness 之间有时间窗，
     * 期间会话可能被删除——执行前再查一次，把「评估已删除会话」的概率降到最低。
     */
    @Override
    protected void processBusiness(VoiceEvaluatePayload payload) {
        Long sessionId = payload.sessionId();
        if (!sessionRepository.existsById(sessionId)) {
            log.warn("语音面试会话已被删除，跳过评估任务: sessionId={}", sessionId);
            return;
        }
        evaluationService.generateEvaluation(sessionId);
        log.info("语音面试评估完成: sessionId={}", sessionId);
    }

    /**
     * 评估成功后：状态置 COMPLETED。
     */
    @Override
    protected void markCompleted(VoiceEvaluatePayload payload) {
        voiceInterviewService.updateEvaluateStatus(
                payload.sessionId(), AsyncTaskStatus.COMPLETED, null);
    }

    /**
     * 执行失败后：状态置 FAILED 并带错误信息（截断，匹配会话表 500 字符列宽）。
     */
    @Override
    protected void markFailed(VoiceEvaluatePayload payload, String error) {
        voiceInterviewService.updateEvaluateStatus(
                payload.sessionId(), AsyncTaskStatus.FAILED, error);
    }

    /**
     * 失败重试：把任务重新入队（新消息、retryCount 递增）。
     *
     * <p>重试入队本身也失败时（Redis 抖动）：直接把状态置 FAILED——
     * 用户可在前端手动重新触发（POST /evaluation），不死等自动恢复。
     */
    @Override
    protected void retryMessage(VoiceEvaluatePayload payload, int retryCount) {
        Long sessionId = payload.sessionId();
        try {
            Map<String, String> message = Map.of(
                    AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID, sessionId.toString(),
                    AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
            );

            redisService().streamAdd(
                    AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY,
                    message,
                    AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("语音面试评估任务已重新入队: sessionId={}, retryCount={}", sessionId, retryCount);
        } catch (Exception e) {
            log.error("重试入队失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            voiceInterviewService.updateEvaluateStatus(
                    sessionId, AsyncTaskStatus.FAILED, truncateError("重试入队失败: " + e.getMessage()));
        }
    }
}

