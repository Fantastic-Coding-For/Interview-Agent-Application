package com.ywy.interviewagentapplication.modules.voiceinterview.listener;

import com.ywy.interviewagentapplication.common.async.AbstractStreamProducer;
import com.ywy.interviewagentapplication.common.constant.AsyncTaskStreamConstants;
import com.ywy.interviewagentapplication.common.model.AsyncTaskStatus;
import com.ywy.interviewagentapplication.common.transaction.TransactionalExecutor;
import com.ywy.interviewagentapplication.infrastructure.redis.RedisService;
import com.ywy.interviewagentapplication.modules.voiceinterview.service.VoiceInterviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 语音面试评估任务生产者。
 *
 * <h3>职责边界</h3>
 * 面试结束（含断连兜底结束）后，向 Redis Stream 投递评估任务，由消费者
 * 异步执行 LLM 评估。只负责「把任务放进队列」，不关心评估逻辑本身。
 *
 * <h3>@Lazy 的循环依赖</h3>
 * {@code VoiceInterviewService} 依赖本 Producer（发送评估任务），
 * 而本类的 {@code onSendFailed} 又回调 {@code VoiceInterviewService}
 * 更新失败状态——构成构造器注入的循环依赖。
 * {@code @Lazy} 使 Spring 为 VoiceInterviewService 注入代理，
 * 在首次真正调用方法时才解析目标 Bean，打破启动期循环。
 *
 * <h3>发送失败补偿</h3>
 * Redis 投递失败时（如连接抖动），onSendFailed 在<b>独立新事务</b>中
 * 将会话的评估状态置为 FAILED 并截断错误信息——让前端轮询能立刻看到
 * 「评估失败」而非永远卡在 PENDING。错误信息截断到 500 字符以内，
 * 匹配会话表 evaluate_error 列的长度约束，防止超长信息写入失败。
 */
@Slf4j
@Component
public class VoiceEvaluateStreamProducer extends AbstractStreamProducer<String> {

    private final VoiceInterviewService voiceInterviewService;
    private final TransactionalExecutor transactionalExecutor;

    public VoiceEvaluateStreamProducer(RedisService redisService,
                                       @Lazy VoiceInterviewService voiceInterviewService,
                                       TransactionalExecutor transactionalExecutor) {
        super(redisService);
        this.voiceInterviewService = voiceInterviewService;
        this.transactionalExecutor = transactionalExecutor;
    }

    /**
     * 投递一个会话的评估任务。
     *
     * <p>调用时机：在「会话状态已持久化」之后调用（Service 层保证 afterCommit 投递）。
     */
    public void sendEvaluateTask(String sessionId) {
        sendTask(sessionId);
    }

    @Override
    protected String taskDisplayName() {
        return "语音面试评估";
    }

    /**
     * 任务所在的 Redis Stream key。
     */
    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.VOICE_EVALUATE_STREAM_KEY;
    }

    /**
     * 构建 Stream 消息体。
     *
     * <p>消息只携带会话ID与重试计数——<b>负载最小化原则</b>：
     * 评估所需的全部上下文（对话历史、技能、简历）都持久化在数据库中，
     * 消费者按 ID 现查现用。消息体轻量使队列吞吐高、消息体积稳定，
     * 也避免了「投递时快照 vs 消费时状态」不一致的经典问题。
     */
    @Override
    protected Map<String, String> buildMessage(String sessionId) {
        return Map.of(
                AsyncTaskStreamConstants.FIELD_VOICE_SESSION_ID, sessionId,
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    /**
     * 任务幂等标识（日志与去重用）。
     */
    @Override
    protected String payloadIdentifier(String sessionId) {
        return "voiceSessionId=" + sessionId;
    }

    /**
     * 发送失败回调：在独立事务中将评估状态置为 FAILED。
     *
     * <p>使用 {@code runRequiresNew} 的原因：投递通常发生在 afterCommit 之后，
     * 但若在事务内发送失败，必须用新事务落库——原事务可能已回滚或已提交，
     * 独立事务保证「失败状态一定能写进去」。
     */
    @Override
    protected void onSendFailed(String sessionId, String error) {
        transactionalExecutor.runRequiresNew(() -> voiceInterviewService.updateEvaluateStatus(
                Long.parseLong(sessionId), AsyncTaskStatus.FAILED, truncateError(error)));
    }
}

