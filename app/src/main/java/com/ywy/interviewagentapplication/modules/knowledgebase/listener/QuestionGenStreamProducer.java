package com.ywy.interviewagentapplication.modules.knowledgebase.listener;

import com.ywy.interviewagentapplication.common.async.AbstractStreamProducer;
import com.ywy.interviewagentapplication.common.constant.AsyncTaskStreamConstants;
import com.ywy.interviewagentapplication.infrastructure.redis.RedisService;
import com.ywy.interviewagentapplication.modules.knowledgebase.service.QuestionGenerationStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 知识库题目生成任务生产者：将生成任务发送到 Redis Stream。
 *
 * <h3>与向量化生产者的差异：携带 taskId</h3>
 * 消息负载包含 kbId + taskId（而非向量化的 kbId + content）：
 * <ul>
 *   <li><b>taskId</b>：任务幂等标识——数据库记录当前有效的 taskId，
 *       消息中的 taskId 与数据库不一致时任务被丢弃（防止旧任务覆盖新任务）</li>
 *   <li><b>不携带配置</b>：生成配置存储在数据库（questionGenConfig 字段），
 *       消费者从数据库读取——消息保持轻量，配置的变更不需要重发消息</li>
 * </ul>
 *
 * <h3>发送失败的处理</h3>
 * {@code onSendFailed} 将任务标记为 FAILED——状态记录在数据库，用户可通过前端看到失败状态并重新提交。
 *
 * @see QuestionGenStreamConsumer 对应的消费者
 * @see QuestionGenerationStateService 任务状态管理（幂等的核心）
 */
@Slf4j
@Component
public class QuestionGenStreamProducer
        extends AbstractStreamProducer<QuestionGenStreamProducer.QuestionGenTaskPayload> {

    private final QuestionGenerationStateService stateService;

    /** 任务负载：kbId + taskId + 重试计数 */
    public record QuestionGenTaskPayload(Long kbId, String taskId, int retryCount) {
    }

    public QuestionGenStreamProducer(
            RedisService redisService,
            QuestionGenerationStateService stateService
    ) {
        super(redisService);
        this.stateService = stateService;
    }

    /**
     * 发送生成任务（首次投递，retryCount = 0）。
     * @return true 如果消息成功发送
     */
    public boolean sendGenerateTask(Long kbId, String taskId) {
        return sendGenerateTask(kbId, taskId, 0);
    }

    /**
     * 发送生成任务（可指定重试计数）。
     * @return true 如果消息成功发送
     */
    public boolean sendGenerateTask(Long kbId, String taskId, int retryCount) {
        return sendTask(new QuestionGenTaskPayload(kbId, taskId, retryCount));
    }

    @Override
    protected String taskDisplayName() {
        return "题目生成";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_QUESTION_GEN_STREAM_KEY;
    }

    /**
     * 构建消息体：kbId + taskId + retryCount。
     * 不携带生成配置，配置在数据库中，消费者按 taskId 读取（见 QuestionGenerationStateService.getConfig）。
     */
    @Override
    protected Map<String, String> buildMessage(QuestionGenTaskPayload payload) {
        return Map.of(
                AsyncTaskStreamConstants.FIELD_KB_ID, payload.kbId().toString(),
                AsyncTaskStreamConstants.FIELD_TASK_ID, payload.taskId(),
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(payload.retryCount())
        );
    }

    @Override
    protected String payloadIdentifier(QuestionGenTaskPayload payload) {
        return "kbId=" + payload.kbId() + ", taskId=" + payload.taskId();
    }

    /**
     * 发送失败回调，标记任务失败。
     * <p>
     * 保护逻辑：检查 taskId 是否匹配，或是否状态为 COMPLETED（如果任务已被新任务替换，或任务已经完成，则不修改）。
     */
    @Override
    protected void onSendFailed(QuestionGenTaskPayload payload, String error) {
        stateService.markFailed(payload.kbId(), payload.taskId());
    }
}

