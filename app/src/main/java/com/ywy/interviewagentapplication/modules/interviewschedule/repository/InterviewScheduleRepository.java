package com.ywy.interviewagentapplication.modules.interviewschedule.repository;

import com.ywy.interviewagentapplication.modules.interviewschedule.model.InterviewScheduleEntity;
import com.ywy.interviewagentapplication.modules.interviewschedule.model.InterviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试日程 Repository。
 *
 * <h3>查询方法的两类用途</h3>
 * <ul>
 *   <li><b>列表/详情</b>：按状态、按时间范围、全量（Controller 的三维过滤）</li>
 *   <li><b>批量维护</b>：JPQL 条件更新（updateStatusByStatusAndInterviewTimeBefore），
 *       供定时任务把过期 PENDING 批量置为 CANCELLED</li>
 * </ul>
 *
 * <h3>为什么批量更新用 JPQL 而不是逐条 save</h3>
 * 过期面试可能成百上千条，逐条 findById + save 是 N 次查询 + N 次更新的
 * N+1 灾难；单条 JPQL UPDATE 在数据库端一次完成，且天然原子。
 * 代价是绕过 JPA 一级缓存（@Modifying 需配合 clearAutomatically 或
 * 调用方保证查询与更新不交叉——本例定时任务不读缓存，无此问题）。
 */
@Repository
public interface InterviewScheduleRepository extends JpaRepository<InterviewScheduleEntity, Long> {
    /**
     * 查找指定状态且面试时间早于给定时间的所有面试日程记录
     */
    List<InterviewScheduleEntity> findByStatusAndInterviewTimeBefore(InterviewStatus status, LocalDateTime time);

    /**
     * 查询指定状态的面试日程记录。
     */
    List<InterviewScheduleEntity> findByStatus(InterviewStatus status);

    /**
     * 按时间范围查询（日历视图：查「这周/这个月」的所有面试）。
     */
    List<InterviewScheduleEntity> findByInterviewTimeBetween(LocalDateTime start, LocalDateTime end);

    /**
     * 批量条件更新：把「指定状态 + 时间早于 cutoff」的记录一次性置为新状态。
     *
     * @return 受影响行数。
     */
    @Modifying
    @Query("UPDATE InterviewScheduleEntity e SET e.status = :newStatus WHERE e.status = :oldStatus AND e.interviewTime < :cutoff")
    int updateStatusByStatusAndInterviewTimeBefore(
            @Param("newStatus") InterviewStatus newStatus,
            @Param("oldStatus") InterviewStatus oldStatus,
            @Param("cutoff") LocalDateTime cutoff);
}

