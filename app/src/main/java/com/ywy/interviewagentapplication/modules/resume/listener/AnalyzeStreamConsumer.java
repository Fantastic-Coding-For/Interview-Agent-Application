package com.ywy.interviewagentapplication.modules.resume.listener;

import com.ywy.interviewagentapplication.common.async.AbstractStreamConsumer;
import com.ywy.interviewagentapplication.common.constant.AsyncTaskStreamConstants;
import com.ywy.interviewagentapplication.common.model.AsyncTaskStatus;
import com.ywy.interviewagentapplication.infrastructure.redis.RedisService;
import com.ywy.interviewagentapplication.modules.interview.model.ResumeAnalysisResponse;
import com.ywy.interviewagentapplication.modules.resume.model.ResumeEntity;
import com.ywy.interviewagentapplication.modules.resume.repository.ResumeRepository;
import com.ywy.interviewagentapplication.modules.resume.service.ResumeGradingService;
import com.ywy.interviewagentapplication.modules.resume.service.ResumePersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * <h1>简历分析 Stream 消费者</h1>
 *
 * <p>负责从 Redis Stream 消费分析任务消息，调用 AI 服务完成简历评分。</p>
 *
 * <h2>在系统中的位置</h2>
 * <pre>
 *  ResumeUploadService  →  AnalyzeStreamProducer  →  Redis Stream  →  【本类】  →  ResumeGradingService
 * </pre>
 *
 * <h2>继承体系</h2>
 * <p>继承自 {@link AbstractStreamConsumer}，该抽象类封装了以下通用逻辑：</p>
 * <ul>
 *   <li><b>消息轮询</b>：使用独立线程从 Redis Stream 拉取消息（{@code XREADGROUP}）</li>
 *   <li><b>异常处理</b>：捕获业务异常，区分可重试错误和不可重试错误</li>
 *   <li><b>重试机制</b>：支持指数退避重试（最大重试次数可配置）</li>
 *   <li><b>ACK 管理</b>：无论任务最终为什么状态，都会执行 ACK，确保消息从 Pending 队列移除。重试消息通过重新 XADD 入队，不影响当前消息的 ACK。</li>
 * </ul>
 * <p>子类只需实现业务相关的钩子方法（Template Method 模式）。</p>
 *
 * <h2>状态机</h2>
 * <p>简历分析状态的生命周期：</p>
 * <pre>
 *   PENDING ──→ PROCESSING ──→ COMPLETED
 *                  │
 *                  └──→ FAILED ──→（手动触发 reanalyze）→ PENDING
 * </pre>
 * <ul>
 *   <li>{@code PENDING}：Producer 发送消息后设置，等待消费</li>
 *   <li>{@code PROCESSING}：Consumer 开始处理时设置（通过 {@link #markProcessing}）</li>
 *   <li>{@code COMPLETED}：AI 分析成功完成（通过 {@link #markCompleted}）</li>
 *   <li>{@code FAILED}：重试耗尽或不可重试的错误（通过 {@link #markFailed}）</li>
 * </ul>
 *
 * <h2>幂等性保证</h2>
 * <p>通过 {@link #shouldSkip} 实现：如果数据库中的简历状态已经是 COMPLETED，
 * 则跳过本次消费。这防止了以下场景的重复分析：</p>
 * <ul>
 *   <li>消息被重复投递（网络重试导致）</li>
 *   <li>Consumer 重启后重新消费已处理的消息</li>
 *   <li>多个 Consumer 实例竞争同一消息的边界情况</li>
 * </ul>
 *
 * <h2>并发模型</h2>
 * <p>父类 {@link AbstractStreamConsumer} 在 {@code @PostConstruct} 时启动一个独立线程，
 * 线程名由 {@link #threadName()} 指定为 {@code "analyze-consumer"}。
 * 默认使用单线程消费，保证处理顺序。</p>
 *
 * <h2>消费者名称</h2>
 * <p>consumerPrefix() + UUID.randomUUID() 前 8 位</p>
 * <h2>消费者组名称</h2>
 * <p>groupName()</p>
 * <h2>消费模式</h2>
 *
 * @see AbstractStreamConsumer 通用的 Redis Stream 消费者抽象基类
 * @see AnalyzeStreamProducer 对应的生产者
 * @see ResumeGradingService AI 评分服务
 */
@Slf4j
@Component
public class AnalyzeStreamConsumer extends AbstractStreamConsumer<AnalyzeStreamConsumer.AnalyzePayload> {

    private final ResumeGradingService gradingService;
    private final ResumePersistenceService persistenceService;
    private final ResumeRepository resumeRepository;

    public AnalyzeStreamConsumer(
            RedisService redisService,
            ResumeGradingService gradingService,
            ResumePersistenceService persistenceService,
            ResumeRepository resumeRepository
    ) {
        super(redisService);
        this.gradingService = gradingService;
        this.persistenceService = persistenceService;
        this.resumeRepository = resumeRepository;
    }

    record AnalyzePayload(Long resumeId, String content) {}

    /**
     * 任务的可读性名称，用于日志输出。
     * @return "简历分析"
     */
    @Override
    protected String taskDisplayName() {
        return "简历分析";
    }

    /**
     * Redis Stream 的 Key 名称。
     * 所有简历分析任务共享同一个 Stream。
     * @return Stream Key（从常量中读取）
     */
    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY;
    }

    /**
     * Redis Stream 消费者组名称。
     * 消费者组允许多个实例协同消费，每个消息只被组内一个消费者处理。
     * @return 消费者组名
     */
    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_GROUP_NAME;
    }

    /**
     * 消费者实例名称前缀。
     * 实际消费者名 = 前缀 + UUID（父类在注册时自动拼接）。
     * 用于区分同一个消费者组内的不同实例。
     * @return 消费者名前缀
     */
    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.RESUME_ANALYZE_CONSUMER_PREFIX;
    }

    /**
     * 消费线程的名称，便于在日志和线程转储中识别。
     * @return "analyze-consumer"
     */
    @Override
    protected String threadName() {
        return "analyze-consumer";
    }

    /**
     * <h3>解析 Stream 消息为强类型的 Payload</h3>
     *
     * <p>Redis Stream 中的消息是 {@code Map<String, String>} 格式（键值对字符串），
     * 本方法负责解析并转换为 {@link AnalyzePayload}。</p>
     *
     * <p>数据校验：如果缺少必要字段（resumeId 或 content），返回 null。</p>
     *
     * @param messageId Stream 消息 ID（用于日志追踪和 ACK）
     * @param data      消息数据（Map 格式，key-value 均为 String）
     * @return 解析后的 Payload，解析失败返回 null
     */
    @Override
    protected AnalyzePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String resumeIdStr = data.get(AsyncTaskStreamConstants.FIELD_RESUME_ID);
        String content = data.get(AsyncTaskStreamConstants.FIELD_CONTENT);
        if (resumeIdStr == null || content == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new AnalyzePayload(Long.parseLong(resumeIdStr), content);
    }

    /**
     * 生成 Payload 的可读标识字符串，用于日志中区分不同消息。
     * @return 格式："resumeId=123"
     */
    @Override
    protected String payloadIdentifier(AnalyzePayload payload) {
        return "resumeId=" + payload.resumeId();
    }

    /**
     * <h3>幂等性检查：判断是否需要跳过该消息</h3>
     *
     * <p>如果数据库中简历的状态已经是 COMPLETED，说明该消息已被处理过
     * （或通过其他途径完成了分析），跳过本次消费。</p>
     *
     * <p>当简历已被删除（findById 返回 empty）时，也返回 true 跳过，
     * 避免处理已不存在的简历。</p>
     *
     * @param payload 消息内容
     * @return true 表示跳过该消息，false 表示正常处理
     */
    @Override
    protected boolean shouldSkip(AnalyzePayload payload) {
        return resumeRepository.findById(payload.resumeId())
                .map(resume -> resume.getAnalyzeStatus() == AsyncTaskStatus.COMPLETED)
                .orElse(true);
    }

    /**
     * <h3>标记为处理中</h3>
     *
     * <p>在开始实际处理前，将数据库中的状态更新为 PROCESSING。
     * 这样前端可以通过状态判断分析是否正在进行中。</p>
     *
     * @param payload 消息内容
     */
    @Override
    protected void markProcessing(AnalyzePayload payload) {
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.PROCESSING, null);
    }

    /**
     * <h3>核心业务处理：执行 AI 分析并保存结果</h3>
     *
     * <p>流程：</p>
     * <ol>
     *   <li>再次检查简历是否存在（处理期间可能被删除），不存在则日志记录然后直接结束</li>
     *   <li>调用 {@link ResumeGradingService#analyzeResume} 执行 AI 评分</li>
     *   <li>再次确认简历未被删除（AI 调用可能耗时数十秒，简历可能被删除），不存在则日志记录然后直接结束</li>
     *   <li>保存分析结果到数据库</li>
     * </ol>
     *
     * @param payload 消息内容
     */
    @Override
    protected void processBusiness(AnalyzePayload payload) {
        Long resumeId = payload.resumeId();
        if (!resumeRepository.existsById(resumeId)) {
            log.warn("简历已被删除，跳过分析任务: resumeId={}", resumeId);
            return;
        }

        ResumeAnalysisResponse analysis = gradingService.analyzeResume(payload.content());
        ResumeEntity resume = resumeRepository.findById(resumeId).orElse(null);
        if (resume == null) {
            log.warn("简历在分析期间被删除，跳过保存结果: resumeId={}", resumeId);
            return;
        }
        persistenceService.saveAnalysis(resume, analysis);
    }

    /**
     * <h3>标记为处理完成</h3>
     * 数据库状态更新为 COMPLETED。
     *
     * @param payload 消息内容
     */
    @Override
    protected void markCompleted(AnalyzePayload payload) {
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.COMPLETED, null);
    }

    /**
     * <h3>标记为处理失败（重试已耗尽）</h3>
     * 数据库状态更新为 FAILED，并记录错误信息。前端可以展示错误并允许手动重试。
     *
     * @param payload 消息内容
     * @param error   错误描述
     */
    @Override
    protected void markFailed(AnalyzePayload payload, String error) {
        updateAnalyzeStatus(payload.resumeId(), AsyncTaskStatus.FAILED, error);
    }

    /**
     * <h3>重试消息：将失败任务重新入队</h3>
     *
     * <p>当 AI 调用失败且还有重试次数时调用。实现方式：</p>
     * <ol>
     *   <li>构造新的 Stream 消息（递增 retryCount）</li>
     *   <li>将消息添加到 Redis Stream 末尾</li>
     *   <li>如果重新入队本身也失败了，则直接标记为 FAILED</li>
     * </ol>
     *
     * <p><b>注意：</b>不修改原消息，而是创建一条新消息。
     * 原消息在重试次数耗尽后会被父类 ACK（不再处理）。</p>
     *
     * @param payload   消息内容
     * @param retryCount 当前重试次数（由父类管理）
     */
    @Override
    protected void retryMessage(AnalyzePayload payload, int retryCount) {
        Long resumeId = payload.resumeId();
        String content = payload.content();
        try {
            Map<String, String> message = Map.of(
                    AsyncTaskStreamConstants.FIELD_RESUME_ID, resumeId.toString(),
                    AsyncTaskStreamConstants.FIELD_CONTENT, content,
                    AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
            );

            redisService().streamAdd(
                    AsyncTaskStreamConstants.RESUME_ANALYZE_STREAM_KEY,
                    message,
                    AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("简历分析任务已重新入队: resumeId={}, retryCount={}", resumeId, retryCount);

        } catch (Exception e) {
            log.error("重试入队失败: resumeId={}, error={}", resumeId, e.getMessage(), e);
            updateAnalyzeStatus(resumeId, AsyncTaskStatus.FAILED, truncateError("重试入队失败: " + e.getMessage()));
        }
    }

    /**
     * <h3>更新数据库中的分析状态</h3>
     *
     * <p>封装了对数据库状态字段的更新操作。注意：</p>
     * <ul>
     *   <li>使用 {@code findById + ifPresent} 模式，简历不存在时静默跳过</li>
     *   <li>状态更新失败（数据库异常）仅记录日志，不向上层抛出，
     *       避免影响 Stream 的 ACK 流程</li>
     *   <li>这是一个<b>辅助方法</b>，异常被吞掉是设计选择。
     *       无论状态是否更新失败，核心的业务处理（AI 分析）执行后消息都会被 ACK</li>
     * </ul>
     *
     * @param resumeId 简历 ID
     * @param status   新状态
     */
    private void updateAnalyzeStatus(Long resumeId, AsyncTaskStatus status, String error) {
        try {
            resumeRepository.findById(resumeId).ifPresent(resume -> {
                resume.setAnalyzeStatus(status);
                resume.setAnalyzeError(error);
                resumeRepository.save(resume);
                log.debug("分析状态已更新: resumeId={}, status={}", resumeId, status);
            });
        } catch (Exception e) {
            log.error("更新分析状态失败: resumeId={}, status={}, error={}", resumeId, status, e.getMessage(), e);
        }
    }

}

