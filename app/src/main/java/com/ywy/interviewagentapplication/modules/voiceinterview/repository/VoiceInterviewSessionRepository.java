package com.ywy.interviewagentapplication.modules.voiceinterview.repository;

import com.ywy.interviewagentapplication.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import com.ywy.interviewagentapplication.modules.voiceinterview.model.VoiceInterviewSessionEntity.InterviewPhase;
import com.ywy.interviewagentapplication.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 语音面试会话Repository。
 */
@Repository
public interface VoiceInterviewSessionRepository extends JpaRepository<VoiceInterviewSessionEntity, Long> {
    /**
     * 悲观锁读取指定的会话（SELECT ... FOR UPDATE）。
     * <p>在事务内锁定会话行，防止并发写入（如两个压缩任务同时 UPSERT 摘要行）造成摘要覆盖或重复创建。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM VoiceInterviewSessionEntity s WHERE s.id = :sessionId")
    Optional<VoiceInterviewSessionEntity> findByIdForUpdate(@Param("sessionId") Long sessionId);

    /**
     * 根据用户ID查找所有会话，按会话开始时间倒序。
     */
    List<VoiceInterviewSessionEntity> findByUserIdOrderByStartTimeDesc(String userId);

    /**
     * 查找指定异步任务状态且结束时间早于给定时间的会话。
     *
     * <p>此方法的 status 参数类型是 {@code AsyncTaskStatus}（评估状态枚举），
     * 而不是 {@link VoiceInterviewSessionStatus}（会话状态枚举）——
     * 若按会话状态过滤，请使用下方 {@code findByStatusAndStartTimeBefore}；
     */
    Optional<VoiceInterviewSessionEntity> findByStatusAndEndTimeBefore(
            com.ywy.interviewagentapplication.common.model.AsyncTaskStatus status,
            LocalDateTime time
    );

    /**
     * 按用户查找全部会话，按更新时间倒序（列表页默认排序：最近活跃的在前）。
     */
    List<VoiceInterviewSessionEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    /**
     * 按用户 + 会话状态过滤语音面试会话实体，按更新时间倒序。
     */
    List<VoiceInterviewSessionEntity> findByUserIdAndStatusOrderByUpdatedAtDesc(
            String userId,
            VoiceInterviewSessionStatus status
    );

    /**
     * 查找指定会话状态且开始时间早于给定时间的会话。
     *
     * <p>定时清理扫描：捞出「超过阈值时长仍处于该状态」的会话
     * （如 IN_PROGRESS 超过 2 小时视为断连残留，见 cleanupStaleSessions）。
     */
    List<VoiceInterviewSessionEntity> findByStatusAndStartTimeBefore(
            VoiceInterviewSessionStatus status,
            LocalDateTime time
    );

    /**
     * 查找指定评估状态且更新时间早于给定时间的会话。
     *
     * <p>评估任务的过期扫描：PENDING 超时重投递、PROCESSING 超时置 FAILED，
     * 用 updatedAt 而非开始时间判断「卡住」——评估状态每次变更都会刷新 updatedAt
     * （JPA @PreUpdate），因此更新时间停滞 = 任务确实卡住。
     */
    List<VoiceInterviewSessionEntity> findByEvaluateStatusAndUpdatedAtBefore(
            com.ywy.interviewagentapplication.common.model.AsyncTaskStatus evaluateStatus,
            LocalDateTime time
    );
}

