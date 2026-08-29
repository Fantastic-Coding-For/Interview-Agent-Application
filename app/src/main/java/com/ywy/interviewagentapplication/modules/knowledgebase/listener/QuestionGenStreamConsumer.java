package com.ywy.interviewagentapplication.modules.knowledgebase.listener;

import com.ywy.interviewagentapplication.common.async.AbstractStreamConsumer;
import com.ywy.interviewagentapplication.common.constant.AsyncTaskStreamConstants;
import com.ywy.interviewagentapplication.infrastructure.redis.RedisService;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.QuestionGenerationConfig;
import com.ywy.interviewagentapplication.modules.knowledgebase.service.KnowledgeBaseQuestionGenerationService;
import com.ywy.interviewagentapplication.modules.knowledgebase.service.QuestionGenerationStateService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 知识库题目生成 Stream 消费者。
 *
 * <h3>与向量化消费者的关键差异</h3>
 * 向量化消费者的状态标记是"尽力而为"（更新失败不阻塞流程）；
 * 题目生成使用<b>原子领取</b>模式：
 * <ol>
 *   <li>{@link #tryMarkProcessing}：以数据库状态转换（QUEUED → PROCESSING）
 *       作为"领取任务"的原子操作——通过悲观锁 + 状态条件更新保证
 *       同一任务只被一个消费者领取</li>
 *   <li>领取失败（状态不匹配）→ 跳过</li>
 * </ol>
 * 为什么需要原子领取？题目生成会<b>替换整个题库</b>（删除旧题+写入新题），
 * 重复执行会造成题目重复。向量化有"两阶段提交"保护，题目生成的
 * 保护在领取环节（数据库状态机）。
 *
 * <h3>任务失效检测（taskId 匹配）</h3>
 * 用户可能在任务排队期间重新提交生成请求（新 taskId 覆盖旧 taskId）。
 * 旧消息被消费时，tryMarkProcessing 的 taskId 校验失败 → 跳过旧任务。
 * 这保证了"最后一次提交获胜"的语义。
 *
 * <h3>空实现的模板方法</h3>
 * markProcessing / markCompleted 是空实现——状态转换只允许在
 * tryMarkProcessing（领取时）和 replaceQuestionsAndComplete
 * （生成完成后的事务中）完成。
 *
 * @see QuestionGenerationStateService 状态机（幂等的核心）
 */
@Slf4j
@Component
public class QuestionGenStreamConsumer
        extends AbstractStreamConsumer<QuestionGenStreamConsumer.QuestionGenPayload> {

    private final KnowledgeBaseQuestionGenerationService generationService;
    private final QuestionGenerationStateService stateService;
    private final QuestionGenStreamProducer producer;

    /** 消息负载：kbId + taskId */
    public record QuestionGenPayload(Long kbId, String taskId) {
    }

    public QuestionGenStreamConsumer(
            RedisService redisService,
            KnowledgeBaseQuestionGenerationService generationService,
            QuestionGenerationStateService stateService,
            QuestionGenStreamProducer producer
    ) {
        super(redisService);
        this.generationService = generationService;
        this.stateService = stateService;
        this.producer = producer;
    }

    @Override
    protected String taskDisplayName() {
        return "题目生成";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_QUESTION_GEN_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.KB_QUESTION_GEN_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.KB_QUESTION_GEN_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "question-gen-consumer";
    }

    /**
     * 解析消息体。
     * 格式错误（缺少 kbId 或 taskId）返回 null，父类跳过该消息。
     */
    @Override
    protected QuestionGenPayload parsePayload(
            StreamMessageId messageId,
            Map<String, String> data
    ) {
        String kbId = data.get(AsyncTaskStreamConstants.FIELD_KB_ID);
        String taskId = data.get(AsyncTaskStreamConstants.FIELD_TASK_ID);
        if (kbId == null || taskId == null) {
            log.warn("题目生成消息格式错误，丢弃: messageId={}", messageId);
            return null;
        }
        return new QuestionGenPayload(Long.parseLong(kbId), taskId);
    }

    @Override
    protected String payloadIdentifier(QuestionGenPayload payload) {
        return "kbId=" + payload.kbId() + ", taskId=" + payload.taskId();
    }

    /** 空实现——状态转换在 tryMarkProcessing 中原子完成 */
    @Override
    protected void markProcessing(QuestionGenPayload payload) {
        // 题目生成使用 tryMarkProcessing 原子领取。
    }

    /**
     * 原子领取任务：QUEUED → PROCESSING。
     * <p>
     * 消费者通过此方法"认领"任务：只有状态转换成功的消费者才有权执行问题生成任务。悲观锁 + 条件更新保证并发下只有一个消费者成功。
     */
    @Override
    protected boolean tryMarkProcessing(QuestionGenPayload payload) {
        return stateService.tryMarkProcessing(payload.kbId(), payload.taskId());
    }

    /**
     * 执行批量题目生成（核心逻辑）。
     * 从数据库读取生成配置（按 taskId 校验），委托生成服务执行。
     */
    @Override
    protected void processBusiness(QuestionGenPayload payload) {
        QuestionGenerationConfig config = stateService.getConfig(payload.kbId(), payload.taskId());
        generationService.executeGeneration(payload.kbId(), payload.taskId(), config);
    }

    @Override
    protected void markCompleted(QuestionGenPayload payload) {
        // 题目替换和 COMPLETED 状态已在同一事务中提交。
    }

    /**
     * 原子标记失败（任意活跃状态 → FAILED）。
     * <p>
     * 保护逻辑：检查 taskId 是否匹配，或是否状态为 COMPLETED（如果任务已被新任务替换，或任务已经完成，则不修改）。
     */
    @Override
    protected void markFailed(QuestionGenPayload payload, String error) {
        stateService.markFailed(payload.kbId(), payload.taskId());
    }

    /**
     * 失败重试：先原子地尝试重置状态，成功后重新入队。
     * <p>
     * resetForRetry 返回 false（任务已失效/被替换）时不再重试，避免旧任务的重试消息在新任务执行期间造成干扰。
     */
    @Override
    protected void retryMessage(QuestionGenPayload payload, int retryCount) {
        if (!stateService.resetForRetry(payload.kbId(), payload.taskId())) {
            log.info("题目生成任务已失效，不再重试: kbId={}, taskId={}",
                    payload.kbId(), payload.taskId());
            return;
        }
        producer.sendGenerateTask(payload.kbId(), payload.taskId(), retryCount);
    }
}

