package com.ywy.interviewagentapplication.modules.knowledgebase.listener;

import com.ywy.interviewagentapplication.common.async.AbstractStreamConsumer;
import com.ywy.interviewagentapplication.common.constant.AsyncTaskStreamConstants;
import com.ywy.interviewagentapplication.infrastructure.redis.RedisService;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.VectorStatus;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.KnowledgeBaseRepository;
import com.ywy.interviewagentapplication.modules.knowledgebase.service.KnowledgeBaseVectorService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 知识库向量化 Stream 消费者：消费向量化任务并执行。失败时通过 {@link #retryMessage} 重新入队（带重试计数）。
 *
 * <h3>为什么向量化服务不直接标注 @Async 而用 Stream？</h3>
 * <ul>
 *   <li><b>持久性</b>：Stream 中的任务在 Redis 重启后仍然存在（AOF/RDB 持久化），
 *       而 @Async 任务在 JVM 内存中，进程崩溃即丢失</li>
 *   <li><b>水平扩展</b>：多个应用实例通过消费者组自动负载均衡，向量化任务可以在多台机器上并行处理</li>
 *   <li><b>可观测性</b>：可以随时用 XRANGE 查看积压的任务数和内容</li>
 * </ul>
 *
 * @see VectorizeStreamProducer 对应的生产者
 * @see KnowledgeBaseVectorService 向量化核心逻辑
 */
@Slf4j
@Component
public class VectorizeStreamConsumer extends AbstractStreamConsumer<VectorizeStreamConsumer.VectorizePayload> {

    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public VectorizeStreamConsumer(
            RedisService redisService,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseRepository knowledgeBaseRepository
    ) {
        super(redisService);
        this.vectorService = vectorService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    /** 向量化任务负载：知识库 ID + 文档解析内容 */
    record VectorizePayload(Long kbId, String content) {}

    @Override
    protected String taskDisplayName() {
        return "向量化";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "vectorize-consumer";
    }

    /**
     * 解析消息体为 VectorizePayload。
     * <p>
     * 消息格式：{@code {kbId: "123", content: "文档全文...", retryCount: "0"}}
     * kbId 从字符串解析为 Long。kbId 或 content 缺失时返回 null。
     */
    @Override
    protected VectorizePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String kbIdStr = data.get(AsyncTaskStreamConstants.FIELD_KB_ID);
        String content = data.get(AsyncTaskStreamConstants.FIELD_CONTENT);
        if (kbIdStr == null || content == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new VectorizePayload(Long.parseLong(kbIdStr), content);
    }

    @Override
    protected String payloadIdentifier(VectorizePayload payload) {
        return "kbId=" + payload.kbId();
    }

    /**
     * 幂等检查——已完成的或已删除的知识库跳过。
     * <p>
     * 重复消费的场景：消费者崩溃后 Pending 消息被其他消费者回收、
     * 网络重试导致重复发送。检查数据库中的 vectorStatus：
     * 已经 COMPLETED 的任务无需再次向量化。
     * <p>
     * {@code orElse(true)}：知识库已删除时也跳过。
     */
    @Override
    protected boolean shouldSkip(VectorizePayload payload) {
        return knowledgeBaseRepository.findById(payload.kbId())
                .map(kb -> kb.getVectorStatus() == VectorStatus.COMPLETED)
                .orElse(true);
    }

    @Override
    protected void markProcessing(VectorizePayload payload) {
        updateVectorStatus(payload.kbId(), VectorStatus.PROCESSING, null);
    }

    /**
     * 执行向量化。
     * <p>
     * 消费前再次次检查知识库存在性：从入队到消费可能间隔较长时间（如 Stream 积压时），期间知识库可能已被删除。
     * shouldSkip 中的 orElse(true) 已覆盖此场景，但这里再检查一次是为了防御性编程（两个方法可能被单独修改）。
     */
    @Override
    protected void processBusiness(VectorizePayload payload) {
        Long kbId = payload.kbId();
        if (!knowledgeBaseRepository.existsById(kbId)) {
            log.warn("知识库已被删除，跳过向量化任务: kbId={}", kbId);
            return;
        }
        vectorService.vectorizeAndStore(payload.kbId(), payload.content());
    }

    @Override
    protected void markCompleted(VectorizePayload payload) {
        updateVectorStatus(payload.kbId(), VectorStatus.COMPLETED, null);
    }

    @Override
    protected void markFailed(VectorizePayload payload, String error) {
        updateVectorStatus(payload.kbId(), VectorStatus.FAILED, error);
    }

    /**
     * 失败重试：将任务重新入队到 Stream 尾部。
     * <p>
     * 重试时携带完整的文档内容。重试次数由父类控制，超过上限后不再重试（标记 FAILED）。
     */
    @Override
    protected void retryMessage(VectorizePayload payload, int retryCount) {
        Long kbId = payload.kbId();
        String content = payload.content();
        try {
            Map<String, String> message = Map.of(
                    AsyncTaskStreamConstants.FIELD_KB_ID, kbId.toString(),
                    AsyncTaskStreamConstants.FIELD_CONTENT, content,
                    AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
            );

            redisService().streamAdd(
                    AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY,
                    message,
                    AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("向量化任务已重新入队: kbId={}, retryCount={}", kbId, retryCount);

        } catch (Exception e) {
            log.error("重试入队失败: kbId={}, error={}", kbId, e.getMessage(), e);
            updateVectorStatus(kbId, VectorStatus.FAILED, truncateError("重试入队失败: " + e.getMessage()));
        }
    }

    /**
     * 更新向量化状态到数据库。
     * <p>
     * 更新失败只记录日志不抛异常：状态更新是辅助性的，不应因状态记录失败导致消费流程报错（真正的向量化结果才是核心）。
     */
    private void updateVectorStatus(Long kbId, VectorStatus status, String error) {
        try {
            knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
                kb.setVectorStatus(status);
                kb.setVectorError(error);
                knowledgeBaseRepository.save(kb);
                log.debug("向量化状态已更新: kbId={}, status={}", kbId, status);
            });
        } catch (Exception e) {
            log.error("更新向量化状态失败: kbId={}, status={}, error={}", kbId, status, e.getMessage(), e);
        }
    }

}

