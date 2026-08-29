package com.ywy.interviewagentapplication.modules.interview.listener;

import com.ywy.interviewagentapplication.common.async.AbstractStreamProducer;
import com.ywy.interviewagentapplication.common.constant.AsyncTaskStreamConstants;
import com.ywy.interviewagentapplication.common.model.AsyncTaskStatus;
import com.ywy.interviewagentapplication.common.transaction.TransactionalExecutor;
import com.ywy.interviewagentapplication.infrastructure.redis.RedisService;
import com.ywy.interviewagentapplication.modules.interview.repository.InterviewSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 面试评估任务生产者：负责将评估任务发送到 Redis Stream。
 *
 * <h3>发送失败的降级策略</h3>
 * {@link #onSendFailed} 方法在独立事务中将评估状态设为 FAILED，
 * 确保"发送失败"这一事实可被记录到数据库，后续可通过重试或手动触发来补救。
 * 使用 {@code runRequiresNew()} 保证独立事务：即使外层事务回滚，失败状态的写入也保留。
 *
 * @see EvaluateStreamConsumer 对应的消费者
 * @see AbstractStreamProducer 父类模板
 */
@Slf4j
@Component
public class EvaluateStreamProducer extends AbstractStreamProducer<String> {

    private final InterviewSessionRepository sessionRepository;
    private final TransactionalExecutor transactionalExecutor;

    public EvaluateStreamProducer(
            RedisService redisService,
            InterviewSessionRepository sessionRepository,
            TransactionalExecutor transactionalExecutor
    ) {
        super(redisService);
        this.sessionRepository = sessionRepository;
        this.transactionalExecutor = transactionalExecutor;
    }

    /**
     * 发送评估任务到 Redis Stream
     *
     * @param sessionId 面试会话ID
     */
    public void sendEvaluateTask(String sessionId) {
        sendTask(sessionId);
    }

    @Override
    protected String taskDisplayName() {
        return "评估";
    }

    /**
     * 构建发送到 Stream 的消息体。
     * <p>
     * 消息格式：{@code {sessionId: "...", retryCount: "0"}}
     * 只有两个字段：sessionId（任务标识）和 retryCount（重试计数，初始为0）。
     * 不携带其他数据（如问题列表）是因为：
     * <ul>
     *   <li>Keep message small——消费者从数据库加载完整数据</li>
     *   <li>避免消息体过时：数据库中的数据才是最新的</li>
     * </ul>
     */
    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.INTERVIEW_EVALUATE_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(String sessionId) {
        return Map.of(
                AsyncTaskStreamConstants.FIELD_SESSION_ID, sessionId,
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(String sessionId) {
        return "sessionId=" + sessionId;
    }

    /**
     * 发送失败回调，在独立事务中标记评估状态为 FAILED。
     * <p>
     * 使用 {@code runRequiresNew} 创建独立事务：即使 Producer 所在的事务回滚，失败状态的写入也会执行。
     * <p>
     * 错误信息截断到 500 字符（父类 truncateError 方法），防止超长异常堆栈撑爆数据库字段。
     */
    @Override
    protected void onSendFailed(String sessionId, String error) {
        transactionalExecutor.runRequiresNew(
                () -> updateEvaluateStatus(sessionId, AsyncTaskStatus.FAILED, truncateError(error)));
    }

    /**
     * 更新评估状态到数据库。
     * <p>
     * 错误信息超过 500 字符时截断：数据库字段长度有限，且前 500 字符足够定位问题根因（通常是异常类型 + 前几行堆栈）。
     */
    private void updateEvaluateStatus(String sessionId, AsyncTaskStatus status, String error) {
        sessionRepository.findBySessionId(sessionId).ifPresent(session -> {
            session.setEvaluateStatus(status);
            if (error != null) {
                session.setEvaluateError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            sessionRepository.save(session);
        });
    }
}

