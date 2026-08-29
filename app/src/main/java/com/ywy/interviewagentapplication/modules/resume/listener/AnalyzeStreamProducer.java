package com.ywy.interviewagentapplication.modules.resume.listener;

import com.ywy.interviewagentapplication.common.async.AbstractStreamProducer;
import com.ywy.interviewagentapplication.common.constant.AsyncTaskStreamConstants;
import com.ywy.interviewagentapplication.common.model.AsyncTaskStatus;
import com.ywy.interviewagentapplication.common.transaction.TransactionalExecutor;
import com.ywy.interviewagentapplication.infrastructure.redis.RedisService;
import com.ywy.interviewagentapplication.modules.resume.repository.ResumeRepository;
import com.ywy.interviewagentapplication.modules.resume.service.ResumeUploadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * <h1>简历分析任务生产者</h1>
 *
 * <p>负责将简历分析任务发送到 Redis Stream，是异步分析流程的起点。</p>
 *
 * <h2>在系统中的位置</h2>
 * <pre>
 *  ResumeUploadService  →  【本类】  →  Redis Stream  →  AnalyzeStreamConsumer  →  ResumeGradingService
 * </pre>
 *
 * <h2>继承体系</h2>
 * <p>继承自 {@link AbstractStreamProducer}，该抽象类封装了以下通用逻辑：</p>
 * <ul>
 *   <li><b>消息构造</b>：子类只需实现 {@link #buildMessage} 方法定义消息体格式</li>
 *   <li><b>Stream 追加</b>：使用 {@code XADD} 命令将消息写入 Redis Stream</li>
 *   <li><b>异常处理</b>：发送失败时回调 {@link #onSendFailed}，子类自定义失败处理逻辑</li>
 *   <li><b>日志追踪</b>：自动记录发送日志和失败日志</li>
 * </ul>
 * <p>子类只需实现业务相关的钩子方法（Template Method 模式）。</p>
 *
 * <h2>失败处理策略</h2>
 * <p>当消息发送到 Redis Stream 失败时，通过 {@link #onSendFailed} 回调：通过<b>独立事务</b>更新数据库中指定 ID 的简历状态和错误信息（安全处理为 500 字符以内）。</p>
 *
 * @see AbstractStreamProducer 通用的 Redis Stream 生产者抽象基类
 * @see AnalyzeStreamConsumer 对应的消费者
 * @see ResumeUploadService 简历上传服务
 */
@Slf4j
@Component
public class AnalyzeStreamProducer extends AbstractStreamProducer<AnalyzeStreamProducer.AnalyzeTaskPayload> {

    private final ResumeRepository resumeRepository;
    private final TransactionalExecutor transactionalExecutor;

    record AnalyzeTaskPayload(Long resumeId, String content) {}

    public AnalyzeStreamProducer(
            RedisService redisService,
            ResumeRepository resumeRepository,
            TransactionalExecutor transactionalExecutor
    ) {
        super(redisService);
        this.resumeRepository = resumeRepository;
        this.transactionalExecutor = transactionalExecutor;
    }

    /**
     * 发送分析任务到 Redis Stream
     *
     * @param resumeId 简历ID
     * @param content  简历内容
     */
    public void sendAnalyzeTask(Long resumeId, String content) {
        sendTask(new AnalyzeTaskPayload(resumeId, content));
    }

    @Override
    protected String taskDisplayName() {
        return "分析";
    }

    /**
     * Redis Stream 的 Key 名称。
     * 必须与 Consumer 的 {@code streamKey()} 返回相同值，否则消息无法被消费。
     * @return Stream Key
     */
    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY;
    }

    /**
     * <h3>构造 Redis Stream 消息体</h3>
     *
     * <p>将强类型的 Payload 转换为 Redis Stream 所需的 {@code Map<String, String>} 格式。</p>
     *
     * <p>消息字段说明：</p>
     * <ul>
     *   <li>{@code resumeId}：简历 ID</li>
     *   <li>{@code content}：简历文本内容</li>
     *   <li>{@code retryCount}：重试次数，初始为 "0"，重试时由 Consumer 递增</li>
     * </ul>
     *
     * @param payload 任务 Payload
     * @return Redis Stream 消息（key-value 均为 String）
     */
    @Override
    protected Map<String, String> buildMessage(AnalyzeTaskPayload payload) {
        return Map.of(
                AsyncTaskStreamConstants.FIELD_RESUME_ID, payload.resumeId().toString(),
                AsyncTaskStreamConstants.FIELD_CONTENT, payload.content(),
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    /**
     * 生成 Payload 的可读标识，用于日志。
     * @return 格式："resumeId=123"
     */
    @Override
    protected String payloadIdentifier(AnalyzeTaskPayload payload) {
        return "resumeId=" + payload.resumeId();
    }

    /**
     * <h3>发送失败回调</h3>
     *
     * <p>当消息发送到 Redis Stream 失败时（如 Redis 连接断开、内存不足等），
     * 通过<b>独立事务</b>更新数据库中指定 ID 的简历状态和错误信息。</p>
     * <p>使用 {@code runRequiresNew} 确保即使调用方的事务回滚，此状态更新也会提交。</p>
     *
     * @param payload 任务 Payload
     * @param error   错误描述（已被父类截断处理）
     */
    @Override
    protected void onSendFailed(AnalyzeTaskPayload payload, String error) {
        transactionalExecutor.runRequiresNew(
                () -> updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.FAILED, truncateError(error)));
    }

    /**
     * 更新数据库中指定 ID 的简历状态和错误信息
     *
     * <p>错误信息超过 500 字符时会被截断，防止数据库字段溢出。
     * 简历不存在时静默跳过（可能已被删除）。</p>
     *
     * @param resumeId 简历 ID
     * @param status   新状态
     * @param error    错误信息（可为 null）
     */
    private void updateAnalyzeStatus(Long resumeId, AsyncTaskStatus status, String error) {
        resumeRepository.findById(resumeId).ifPresent(resume -> {
            resume.setAnalyzeStatus(status);
            if (error != null) {
                resume.setAnalyzeError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            resumeRepository.save(resume);
        });
    }
}

