package com.ywy.interviewagentapplication.modules.interviewschedule.service;

import com.ywy.interviewagentapplication.modules.interviewschedule.model.InterviewStatus;
import com.ywy.interviewagentapplication.modules.interviewschedule.repository.InterviewScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 面试日程状态定时更新器。
 *
 * <h3>职责单一：把「过期未处理的面试」标记为已取消</h3>
 * PENDING 且 interviewTime 已过的记录意味着「约了但用户没标记结果」——
 * 长时间停留在 PENDING 会污染「即将到来」视图。本任务每小时扫描一次，
 * 批量置为 CANCELLED（用户仍可手动改回，状态更新无终态锁）。
 *
 * <h3>@Transactional 的必要性</h3>
 * @Modifying JPQL 必须运行在事务内才能生效（非事务上下文中执行
 * 需要事务的 DML 会抛 TransactionRequiredException）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleStatusUpdater {

    private final InterviewScheduleRepository repository;

    /**
     * 每小时整点：把「面试时间已过且仍为 PENDING」的记录批量置为 CANCELLED。
     *
     * <p>返回受影响行数只为日志可见性——运维能从日志行数判断
     * 「有多少面试默默过期了」，是业务健康度的间接指标。
     */
    @Scheduled(cron = "0 0 * * * ?")
    @Transactional
    public void updateExpiredInterviews() {
        int updated = repository.updateStatusByStatusAndInterviewTimeBefore(
                InterviewStatus.CANCELLED, InterviewStatus.PENDING, LocalDateTime.now());

        if (updated > 0) {
            log.info("已将 {} 条过期面试标记为已取消", updated);
        }
    }
}

