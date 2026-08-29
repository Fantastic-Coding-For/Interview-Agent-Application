package com.ywy.interviewagentapplication.modules.voiceinterview.model;

import com.ywy.interviewagentapplication.common.model.AsyncTaskStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 语音面试会话实体。
 *
 * <h3>一条记录承载三组状态</h3>
 * <ul>
 *   <li><b>会话状态 status</b>（{@link VoiceInterviewSessionStatus}）：IN_PROGRESS → PAUSED → 恢复/COMPLETED/FAILED，
 *       驱动暂停/恢复/超时清理等生命周期操作</li>
 *   <li><b>阶段 currentPhase</b>（{@link InterviewPhase}）：INTRO → TECH → PROJECT → HR → COMPLETED，
 *       驱动面试官的出题方向，由前端 start_phase 控制消息显式切换</li>
 *   <li><b>评估状态 evaluateStatus</b>（{@link AsyncTaskStatus}）：PENDING → PROCESSING → COMPLETED/FAILED，
 *       异步评估任务的状态被冗余在会话行上——前端轮询一次会话接口即可拿到评估进度，
 *       不必单独查询评估表（评估可能尚未生成）</li>
 * </ul>
 *
 * <h3>冗余的可用时间字段</h3>
 * startTime/endTime/pausedAt/resumedAt 让「实际面试时长」可推算
 * （endTime - startTime，不含暂停期），也支撑按时间维度的会话列表排序与清理扫描。
 */
@Entity
@Table(name = "voice_interview_sessions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceInterviewSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户ID。当前单用户部署场景固定 "default"，预留多用户扩展位 */
    @Column(name = "user_id")
    private String userId;

    /** 岗位方向 */
    @Column(name = "role_type", nullable = false)
    private String roleType;

    /** 技能模板ID（如 java-backend），决定面试官人设与出题素材 */
    @Column(name = "skill_id", length = 64)
    @Builder.Default
    private String skillId = "java-backend";

    /** 面试难度：junior / mid / senior */
    @Column(name = "difficulty", length = 16)
    @Builder.Default
    private String difficulty = "mid";

    /** 自定义 JD 文本（用于 JD 定向出题，与技能模板互补） */
    @Column(name = "custom_jd_text", columnDefinition = "TEXT")
    private String customJdText;

    /** 关联简历ID。非空时 LLM 系统提示词会注入简历内容（见 VoiceInterviewPromptService） */
    @Column(name = "resume_id")
    private Long resumeId;

    @Column(name = "intro_enabled")
    @Builder.Default
    private Boolean introEnabled = true;

    @Column(name = "tech_enabled")
    @Builder.Default
    private Boolean techEnabled = true;

    @Column(name = "project_enabled")
    @Builder.Default
    private Boolean projectEnabled = true;

    @Column(name = "hr_enabled")
    @Builder.Default
    private Boolean hrEnabled = true;

    /** 会话级 LLM 提供商路由（"dashscope" 等），每会话可独立选择 */
    @Column(name = "llm_provider", length = 50)
    @Builder.Default
    private String llmProvider = "dashscope";

    @Column(name = "current_phase")
    @Enumerated(EnumType.STRING)
    private InterviewPhase currentPhase;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private VoiceInterviewSessionStatus status = VoiceInterviewSessionStatus.IN_PROGRESS;

    /** 计划时长（分钟），创建时由请求传入 */
    @Column(name = "planned_duration")
    @Builder.Default
    private Integer plannedDuration = 30;

    /** 实际时长（秒），结束时通过 startTime→endTime 差值计算 */
    @Column(name = "actual_duration")
    private Integer actualDuration;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 更新时间。评估重投递时会被显式刷新，作为「最后活跃时间」参与过期判定 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "paused_at")
    private LocalDateTime pausedAt;

    @Column(name = "resumed_at")
    private LocalDateTime resumedAt;

    /** 异步评估任务状态 */
    @Column(name = "evaluate_status")
    @Enumerated(EnumType.STRING)
    private AsyncTaskStatus evaluateStatus;

    /** 评估失败原因（截断至 500 字符，防超长错误信息撑爆字段） */
    @Column(name = "evaluate_error", length = 500)
    private String evaluateError;

    /**
     * 更新数据库表的元组前自动刷新更新时间。
     */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 插入新行到数据库前自动填充创建时间、更新时间与开始时间。
     */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.startTime = LocalDateTime.now();
    }

    /**
     * 面试阶段枚举。
     *
     * <p>阶段推进有两种驱动方式：
     * <ul>
     *   <li><b>前端显式切换</b>：start_phase 控制消息（当前实现，用户掌控节奏）</li>
     *   <li><b>根据时长和题量规则，服务端自动切换</b>：{@code shouldTransitionToNextPhase}</li>
     * </ul>
     */
    public enum InterviewPhase {
        INTRO, TECH, PROJECT, HR, COMPLETED
    }
}

