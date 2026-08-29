package com.ywy.interviewagentapplication.modules.knowledgebase.listener;

import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.QuestionGenStatus;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.KnowledgeBaseRepository;
import com.ywy.interviewagentapplication.modules.knowledgebase.service.QuestionGenerationStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 题目生成任务的定时恢复器：清理"卡死"任务的看门狗。
 *
 * <h3>需要恢复的故障场景</h3>
 * 任务可能因以下原因永久停留在中间状态：
 * <ul>
 *   <li><b>投递丢失</b>：QUEUED 状态已写入数据库，但 Stream 消息发送失败
 *       （Redis 短暂不可用）——任务永远等不到消费者</li>
 *   <li><b>执行节点崩溃</b>：消费者领取任务（PROCESSING）后 JVM 崩溃——
 *       任务既不会完成也不会失败</li>
 * </ul>
 * 本调度器周期扫描这些"僵尸任务"并重新投递。
 *
 * <h3>两个超时阈值的考量</h3>
 * <ul>
 *   <li><b>QUEUED 超时 2 分钟</b>：正常排队时间应该远小于此——
 *       队列积压超过 2 分钟说明消息可能丢失了</li>
 *   <li><b>PROCESSING 超时 20 分钟</b>：LLM 生成 30 道题的正常耗时
 *       约 1-5 分钟，20 分钟远超正常范围——超时即视为执行节点崩溃</li>
 * </ul>
 * 阈值设计原则：宁可等久一点（阈值宽松），也不误杀正常执行的任务
 * （误杀会导致重复生成，浪费 token）。
 *
 * <h3>防重复投递的保护</h3>
 * 恢复前通过状态服务的 CAS 式检查（touchQueuedForRecovery /
 * resetStaleProcessing）确认任务<b>仍然</b>处于过期状态——
 * 如果在检查间隙任务已被正常处理，重新投递会被跳过。
 * 多实例部署时，各实例的调度器可能同时发现同一任务，
 * 状态服务的条件更新保证只有一个实例成功投递。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuestionGenerationRecoveryScheduler {
    /** QUEUED 状态的最长等待时间（2 分钟）——超过则视为投递丢失 */
    private static final long QUEUED_STALE_MINUTES = 2;
    /** PROCESSING 状态的最长执行时间（20 分钟）——超过则视为节点崩溃 */
    private static final long PROCESSING_STALE_MINUTES = 20;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final QuestionGenerationStateService stateService;
    private final QuestionGenStreamProducer producer;

    /**
     * 定时扫描并恢复卡死任务。
     * <p>
     * fixedDelay = 60s：上次执行完成后 60 秒再执行（而非固定频率——
     * 避免执行时间超过间隔时任务堆积）。initialDelay = 60s：
     * 应用启动 1 分钟后首次执行（留出应用初始化的时间）。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void recoverStaleTasks() {
        LocalDateTime now = LocalDateTime.now();
        recoverQueued(now.minusMinutes(QUEUED_STALE_MINUTES));
        recoverProcessing(now.minusMinutes(PROCESSING_STALE_MINUTES));
    }

    /**
     * 恢复 QUEUED 超时任务：重新投递到 Stream。
     * <p>
     * touchQueuedForRecovery 的双重作用：
     * ①CAS 检查任务状态仍为 QUEUED 且已过期（防重复投递）；
     * ②更新时间戳：投递后任务不再被其他实例的调度器识别为过期。
     */
    private void recoverQueued(LocalDateTime threshold) {
        List<KnowledgeBaseEntity> tasks = knowledgeBaseRepository
                .findStaleQuestionGenerationTasks(QuestionGenStatus.QUEUED, threshold);
        for (KnowledgeBaseEntity task : tasks) {
            String taskId = task.getQuestionGenTaskId();
            if (taskId != null
                    && stateService.touchQueuedForRecovery(task.getId(), taskId, threshold)) {
                producer.sendGenerateTask(task.getId(), taskId);
                log.info("重新投递等待中的题目生成任务: kbId={}, taskId={}", task.getId(), taskId);
            }
        }
    }

    /**
     * 恢复 PROCESSING 卡死任务：重置为 QUEUED 并重新投递。
     * <p>
     * resetStaleProcessing 将 PROCESSING → QUEUED（状态条件更新，
     * 保证只有一个实例成功），成功后更新时间戳，并重新投递。
     * 用 warn 而非 info 日志——PROCESSING 卡死意味着执行节点可能
     * 曾发生崩溃，值得关注。
     */
    private void recoverProcessing(LocalDateTime threshold) {
        List<KnowledgeBaseEntity> tasks = knowledgeBaseRepository
                .findStaleQuestionGenerationTasks(QuestionGenStatus.PROCESSING, threshold);
        for (KnowledgeBaseEntity task : tasks) {
            String taskId = task.getQuestionGenTaskId();
            if (taskId != null
                    && stateService.resetStaleProcessing(task.getId(), taskId, threshold)) {
                producer.sendGenerateTask(task.getId(), taskId);
                log.warn("恢复卡住的题目生成任务: kbId={}, taskId={}", task.getId(), taskId);
            }
        }
    }
}

