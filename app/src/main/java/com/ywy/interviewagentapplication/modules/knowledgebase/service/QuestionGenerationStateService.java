package com.ywy.interviewagentapplication.modules.knowledgebase.service;

import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseQuestionEntity;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.QuestionGenStatus;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.QuestionGenStatusResponse;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.QuestionGenerationConfig;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.VectorStatus;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.KnowledgeBaseQuestionRepository;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 题目生成状态服务：题目生成任务<b>状态机的事务边界</b>。
 *
 * <h3>架构定位</h3>
 * 异步任务的状态管理面临两个核心挑战：
 * <ol>
 *   <li><b>并发安全</b>：消费者线程、恢复调度器、用户新请求可能同时操作状态</li>
 *   <li><b>幂等性</b>：消息可能重复投递（重试/恢复），状态转换不能重复执行</li>
 * </ol>
 * 本服务通过"悲观锁 + 状态条件更新"解决，所有转换都在"锁 + 条件检查"保护下进行：
 * 每次状态转换都在事务中先锁定知识库行（SELECT FOR UPDATE），
 * 再检查<b>当前状态 + taskId 是否匹配预期</b>，不匹配则拒绝转换。
 * <p>
 * 这就是经典的 <b>CAS（Compare-And-Swap）模式</b>在数据库层面的实现：
 * 预期状态 + 预期 taskId 是"比较"条件，状态更新是"交换"操作。
 *
 * <h3>SAFE_FAILURE_MESSAGE 的考量</h3>
 * 失败信息固定为通用文案。真实的异常信息不暴露给用户（可能包含内部结构信息，如 SQL 语句、堆栈路径）。详细错误只写入日志，供开发排查。
 */
@Service
@RequiredArgsConstructor
public class QuestionGenerationStateService {
    /** 对外暴露的统一失败文案 */
    public static final String SAFE_FAILURE_MESSAGE = "题目生成失败，请稍后重试";

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseQuestionRepository questionRepository;
    private final ObjectMapper objectMapper;

    /**
     * 创建生成任务：NONE/COMPLETED/FAILED → QUEUED。
     * <p>
     * 前置校验：
     * <ol>
     *   <li>向量化必须完成：题目基于知识库内容生成，向量化未完成
     *       说明内容尚未建立索引（生成服务依赖向量检索获取内容）</li>
     *   <li>当前无活跃任务：QUEUED/PROCESSING 状态下拒绝重复提交
     *       （一个知识库同一时间只能有一个生成任务）</li>
     * </ol>
     * 创建时生成新的 taskId（UUID）并重置所有任务字段，完全覆盖旧任务在对应知识库中的记录。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param config          生成参数（会被序列化为 JSON 再存储）
     * @return 问题生成任务的状态响应（QUEUED + 新 taskId）
     */
    @Transactional(rollbackFor = Exception.class)
    public QuestionGenStatusResponse createTask(
            Long knowledgeBaseId,
            QuestionGenerationConfig config
    ) {
        KnowledgeBaseEntity kb = lockKnowledgeBase(knowledgeBaseId);
        if (kb.getVectorStatus() != VectorStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库尚未完成向量化");
        }
        if (isActive(kb.getQuestionGenStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库问题正在生成中，请勿重复提交");
        }

        LocalDateTime now = LocalDateTime.now();
        kb.setQuestionGenTaskId(UUID.randomUUID().toString());
        kb.setQuestionGenStatus(QuestionGenStatus.QUEUED);
        kb.setQuestionGenConfig(writeConfig(config));
        kb.setQuestionGenError(null);
        kb.setQuestionGenMessage(null);
        kb.setQuestionGenSavedCount(0);
        kb.setQuestionGenSkippedCount(0);
        kb.setQuestionGenUpdatedAt(now);
        knowledgeBaseRepository.save(kb);
        return toResponse(kb, config);
    }

    /** 查询指定数据库的问题生成任务的状态 */
    @Transactional(readOnly = true)
    public QuestionGenStatusResponse getStatus(Long knowledgeBaseId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
        return toResponse(kb, readConfigOrNull(kb.getQuestionGenConfig()));
    }

    /**
     * 读取任务配置（消费者执行前调用）。
     * <p>
     * taskId 校验：消息中的 taskId 必须与数据库当前 taskId 一致，不一致说明任务已被新任务替换，抛异常终止消费。
     */
    @Transactional(readOnly = true)
    public QuestionGenerationConfig getConfig(Long knowledgeBaseId, String taskId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
        if (!taskId.equals(kb.getQuestionGenTaskId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "题目生成任务已失效");
        }
        QuestionGenerationConfig config = readConfigOrNull(kb.getQuestionGenConfig());
        if (config == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "题目生成配置不存在");
        }
        return config;
    }

    /**
     * 原子领取任务：QUEUED → PROCESSING。
     * <p>
     * 消费者通过此方法"认领"任务：只有状态转换成功的消费者才有权执行问题生成任务。悲观锁 + 条件更新保证并发下只有一个消费者成功。
     *
     * @return true 已成功领取任务
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean tryMarkProcessing(Long knowledgeBaseId, String taskId) {
        KnowledgeBaseEntity kb = lockKnowledgeBaseOrNull(knowledgeBaseId);
        if (!matches(kb, taskId, QuestionGenStatus.QUEUED)) {
            return false;
        }
        kb.setQuestionGenStatus(QuestionGenStatus.PROCESSING);
        kb.setQuestionGenError(null);
        kb.setQuestionGenUpdatedAt(LocalDateTime.now());
        return true;
    }

    /**
     * 重试前重置状态：PROCESSING → QUEUED。
     * 仅当任务仍有效（taskId 匹配 + 状态为 PROCESSING）时成功。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean resetForRetry(Long knowledgeBaseId, String taskId) {
        KnowledgeBaseEntity kb = lockKnowledgeBaseOrNull(knowledgeBaseId);
        if (!matches(kb, taskId, QuestionGenStatus.PROCESSING)) {
            return false;
        }
        kb.setQuestionGenStatus(QuestionGenStatus.QUEUED);
        kb.setQuestionGenUpdatedAt(LocalDateTime.now());
        return true;
    }

    /**
     * 标记失败（任意活跃状态 → FAILED）。
     * <p>
     * 保护逻辑：检查 taskId 是否匹配，或是否状态为 COMPLETED（如果任务已被新任务替换，或任务已经完成，则不修改）。
     * <p>
     * 竞态场景：生成已完成但完成标记晚于失败回调到达时，完成状态优先（成功的事实不能被失败的消息抹掉）。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean markFailed(Long knowledgeBaseId, String taskId) {
        KnowledgeBaseEntity kb = lockKnowledgeBaseOrNull(knowledgeBaseId);
        if (kb == null || !taskId.equals(kb.getQuestionGenTaskId())) {
            return false;
        }
        if (kb.getQuestionGenStatus() == QuestionGenStatus.COMPLETED) {
            return false;
        }
        kb.setQuestionGenStatus(QuestionGenStatus.FAILED);
        kb.setQuestionGenError(SAFE_FAILURE_MESSAGE);
        kb.setQuestionGenUpdatedAt(LocalDateTime.now());
        return true;
    }

    /**
     * 替换题目并完成：PROCESSING → COMPLETED（生成流程的收尾事务）。
     * <p>
     * <b>为什么题目替换和状态完成在同一个事务？</b>
     * 如果分离为两步（先替换题目、再更新状态），中间崩溃会导致
     * "题目已替换但状态仍是 PROCESSING"——恢复调度器会误判为卡死
     * 并重新执行生成，造成二次替换。同一事务保证原子性：
     * 要么全部生效（新题目 + COMPLETED），要么全部回滚（旧题目 + PROCESSING，
     * 等待恢复调度器重新投递）。
     * <p>
     * 操作序列：删旧题 → 存新题 → 更新状态和统计——
     * 全部在行锁保护下完成。
     *
     * @return true 如果提交成功（false = 任务已失效，调用方丢弃结果）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean replaceQuestionsAndComplete(
            Long knowledgeBaseId,
            String taskId,
            List<KnowledgeBaseQuestionEntity> questions,
            int skippedCount
    ) {
        KnowledgeBaseEntity kb = lockKnowledgeBaseOrNull(knowledgeBaseId);
        if (!matches(kb, taskId, QuestionGenStatus.PROCESSING)) {
            return false;
        }

        questions.forEach(question -> question.setKnowledgeBase(kb));
        questionRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
        questionRepository.saveAll(questions);

        int savedCount = questions.size();
        String message = skippedCount > 0
                ? String.format("已生成 %d 道题，跳过 %d 道重复题", savedCount, skippedCount)
                : String.format("已生成 %d 道题", savedCount);
        kb.setQuestionGenStatus(QuestionGenStatus.COMPLETED);
        kb.setQuestionGenError(null);
        kb.setQuestionGenMessage(message);
        kb.setQuestionGenSavedCount(savedCount);
        kb.setQuestionGenSkippedCount(skippedCount);
        kb.setQuestionGenUpdatedAt(LocalDateTime.now());
        return true;
    }

    /**
     * 恢复调度器的 "触摸" 操作：确认 QUEUED 任务已过期并更新时间戳。
     * <p>
     * 更新时间戳的作用：标记"该任务已被恢复调度器处理"。多实例场景下其他实例的调度器看到新时间戳后不会再投递（防止同一任务的重复投递）。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean touchQueuedForRecovery(
            Long knowledgeBaseId,
            String taskId,
            LocalDateTime threshold
    ) {
        KnowledgeBaseEntity kb = lockKnowledgeBaseOrNull(knowledgeBaseId);
        if (!matches(kb, taskId, QuestionGenStatus.QUEUED)
                || !isStale(kb.getQuestionGenUpdatedAt(), threshold)) {
            return false;
        }
        kb.setQuestionGenUpdatedAt(LocalDateTime.now());
        return true;
    }

    /**
     * 恢复调度器的 "卡死重置" 操作：PROCESSING → QUEUED（仅当已过期），并更新时间戳。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean resetStaleProcessing(
            Long knowledgeBaseId,
            String taskId,
            LocalDateTime threshold
    ) {
        KnowledgeBaseEntity kb = lockKnowledgeBaseOrNull(knowledgeBaseId);
        if (!matches(kb, taskId, QuestionGenStatus.PROCESSING)
                || !isStale(kb.getQuestionGenUpdatedAt(), threshold)) {
            return false;
        }
        kb.setQuestionGenStatus(QuestionGenStatus.QUEUED);
        kb.setQuestionGenUpdatedAt(LocalDateTime.now());
        return true;
    }

    /** 悲观锁查询，不存在时抛异常（用于必须存在的场景） */
    private KnowledgeBaseEntity lockKnowledgeBase(Long knowledgeBaseId) {
        return knowledgeBaseRepository.findByIdForUpdate(knowledgeBaseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND));
    }

    /**
     * 悲观锁查询，不存在时返回 null（用于"条件更新"场景）
     */
    private KnowledgeBaseEntity lockKnowledgeBaseOrNull(Long knowledgeBaseId) {
        return knowledgeBaseRepository.findByIdForUpdate(knowledgeBaseId).orElse(null);
    }

    /** CAS 比较条件：kb 存在 + taskId 匹配 + 状态匹配 */
    private boolean matches(
            KnowledgeBaseEntity kb,
            String taskId,
            QuestionGenStatus expectedStatus
    ) {
        return kb != null
                && taskId.equals(kb.getQuestionGenTaskId())
                && kb.getQuestionGenStatus() == expectedStatus;
    }

    /** 活跃状态判断（QUEUED 或 PROCESSING = 任务仍在进行） */
    private boolean isActive(QuestionGenStatus status) {
        return status == QuestionGenStatus.QUEUED || status == QuestionGenStatus.PROCESSING;
    }

    /** 过期判断：updatedAt 为 null 或早于阈值，则视为过期，返回 true。 */
    private boolean isStale(LocalDateTime updatedAt, LocalDateTime threshold) {
        return updatedAt == null || updatedAt.isBefore(threshold);
    }

    /**
     * 将知识库题目生成所需的条件参数快照转换为 JSON 字符串
     */
    private String writeConfig(QuestionGenerationConfig config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化题目生成配置失败", e);
        }
    }

    private QuestionGenerationConfig readConfigOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, QuestionGenerationConfig.class);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "解析题目生成配置失败", e);
        }
    }

    /**
     * 组装问题生成任务的状态响应。
     * 对历史数据做 null 安全处理（旧数据可能缺少 questionGenStatus/count 字段）。
     */
    private QuestionGenStatusResponse toResponse(
            KnowledgeBaseEntity kb,
            QuestionGenerationConfig config
    ) {
        return new QuestionGenStatusResponse(
                kb.getId(),
                kb.getQuestionGenStatus() == null ? QuestionGenStatus.NONE : kb.getQuestionGenStatus(),
                kb.getQuestionGenTaskId(),
                config,
                kb.getQuestionGenSavedCount() == null ? 0 : kb.getQuestionGenSavedCount(),
                kb.getQuestionGenSkippedCount() == null ? 0 : kb.getQuestionGenSkippedCount(),
                kb.getQuestionGenMessage(),
                kb.getQuestionGenError(),
                kb.getQuestionGenUpdatedAt()
        );
    }
}

