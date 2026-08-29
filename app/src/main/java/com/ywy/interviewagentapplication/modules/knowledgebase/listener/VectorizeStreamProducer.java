package com.ywy.interviewagentapplication.modules.knowledgebase.listener;

import com.ywy.interviewagentapplication.common.async.AbstractStreamProducer;
import com.ywy.interviewagentapplication.common.constant.AsyncTaskStreamConstants;
import com.ywy.interviewagentapplication.infrastructure.redis.RedisService;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.VectorStatus;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 向量化任务生产者：将文档向量化任务发送到 Redis Stream。
 *
 * <h3>为什么向量化是异步的？</h3>
 * 文档向量化包含：文本分块 → 逐块调用 Embedding API → 写入向量数据库。
 * 对于 50MB 的文档，这个过程可能耗时数分钟，绝不能阻塞 HTTP 上传请求。
 * 异步化后：
 * <ol>
 *   <li>上传请求只做：验证 → 解析文本 → 存储文件 → 入库 → 入队 → 返回 PENDING</li>
 *   <li>后台消费者执行向量化，完成后将状态更新为 COMPLETED</li>
 *   <li>前端轮询 vectorStatus 获取处理进度</li>
 * </ol>
 *
 * <h3>与面试评估 Stream 的对比</h3>
 * 两者的模式相同（AbstractStreamProducer 模板），但负载不同：
 * 向量化消息携带 <b>完整文档内容</b>（content 字段）而非只传 ID。因为文档内容在上传时已经解析好，直接放入消息避免消费者重新下载文件。而面试评估的内容已经持久化，并不在内存中，需要消费者根据 id 自行拉取。
 * <p>
 * 代价：大文档的消息体可能很大（数百 KB），但 Redis 单条消息的理论上限是 512MB，实际使用远低于此。
 *
 * @see VectorizeStreamConsumer 对应的消费者
 * @see com.ywy.interviewagentapplication.modules.interview.listener.EvaluateStreamProducer 同模式的面试评估生产者
 */
@Slf4j
@Component
public class VectorizeStreamProducer extends AbstractStreamProducer<VectorizeStreamProducer.VectorizeTaskPayload> {

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    /**
     * 向量化任务的负载：kbId + 已解析的文档内容。
     * 将内容直接放入消息，消费者无需再从存储下载。
     */
    record VectorizeTaskPayload(Long kbId, String content) {}

    public VectorizeStreamProducer(RedisService redisService, KnowledgeBaseRepository knowledgeBaseRepository) {
        super(redisService);
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    /**
     * 发送向量化任务到 Redis Stream
     *
     * @param kbId    知识库ID
     * @param content 文档内容
     */
    public void sendVectorizeTask(Long kbId, String content) {
        sendTask(new VectorizeTaskPayload(kbId, content));
    }

    @Override
    protected String taskDisplayName() {
        return "向量化";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY;
    }

    /**
     * 构建消息体：kbId + 完整文档内容 + 重试计数。
     * <p>
     * 文档内容直接放入消息体的权衡：
     * <ul>
     *   <li><b>优点</b>：消费者无需从 RustFS 下载 + Tika 解析（节省一次网络往返和解析开销）；
     *       上传时已经解析过一次，重复解析是浪费</li>
     *   <li><b>缺点</b>：消息体较大（大文档可达数百 KB）；Redis Stream 中积压消息会占用内存</li>
     * </ul>
     * STREAM_MAX_LEN 限制（父类常量）保证 Stream 不会无限膨胀。
     */
    @Override
    protected Map<String, String> buildMessage(VectorizeTaskPayload payload) {
        return Map.of(
                AsyncTaskStreamConstants.FIELD_KB_ID, payload.kbId().toString(),
                AsyncTaskStreamConstants.FIELD_CONTENT, payload.content(),
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0"
        );
    }

    @Override
    protected String payloadIdentifier(VectorizeTaskPayload payload) {
        return "kbId=" + payload.kbId();
    }

    /**
     * 发送失败回调：将向量化状态标记为 FAILED。
     * <p>
     * 与面试评估生产者的区别：这里没有使用独立事务
     * （EvaluateStreamProducer 使用了 TransactionalExecutor.runRequiresNew）。
     * 差异原因：向量化异步任务发送到 Stream 失败时，即使状态不更新也只能重新入队才能继续向量化。至于数据库中存在的僵尸任务会被统一清理。
     */
    @Override
    protected void onSendFailed(VectorizeTaskPayload payload, String error) {
        updateVectorStatus(payload.kbId(), VectorStatus.FAILED, truncateError(error));
    }

    /**
     * 更新数据库中知识库文档的向量化状态
     * 错误信息超过 500 字符时截断，保留前 500 字符足够定位问题。
     */
    private void updateVectorStatus(Long kbId, VectorStatus status, String error) {
        knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
            kb.setVectorStatus(status);
            if (error != null) {
                kb.setVectorError(error.length() > 500 ? error.substring(0, 500) : error);
            }
            knowledgeBaseRepository.save(kb);
        });
    }
}

