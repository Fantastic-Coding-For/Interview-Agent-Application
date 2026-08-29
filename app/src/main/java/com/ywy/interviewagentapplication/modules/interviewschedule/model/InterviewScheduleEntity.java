package com.ywy.interviewagentapplication.modules.interviewschedule.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 面试日程实体：面试邀约信息的最小存储单元。
 *
 * <h3>数据来源的两条路径</h3>
 * <ul>
 *   <li><b>AI/规则解析</b>：用户粘贴面试邀约文本（飞书/腾讯会议/Zoom），
 *       经 {@code InterviewParseService} 提取出结构化字段后落库</li>
 *   <li><b>手动创建</b>：直接 POST 结构化请求（CreateInterviewRequest）</li>
 * </ul>
 *
 * <h3>字段的弹性设计</h3>
 * <ul>
 *   <li>interviewType/roundNumber/interviewer/notes 均可空：不同平台邀约的
 *       信息完整度差异极大，强约束会让解析结果频繁落库失败</li>
 *   <li>meetingLink 用 TEXT：腾讯会议的「会议号+密码」拼接串比 URL 长，
 *       且存在非 URL 形态（纯会议号）</li>
 *   <li>roundNumber 默认 1：解析失败/未提及轮次时按一面处理</li>
 * </ul>
 *
 * <h3>状态机</h3>
 * PENDING（默认）→ COMPLETED/CANCELLED/RESCHEDULED，
 * 其中 PENDING 且时间已过的记录由定时任务批量置为 CANCELLED（见 ScheduleStatusUpdater）。
 */
@Entity
@Table(name = "interview_schedule")
@Data
public class InterviewScheduleEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 公司名称（必需字段：列表展示与按公司检索的主维度） */
    @Column(name = "company_name", nullable = false)
    private String companyName;

    /** 岗位名称（必需字段） */
    @Column(nullable = false)
    private String position;

    /** 面试时间（必需字段：日程排序与「过期判断」的唯一依据） */
    @Column(name = "interview_time", nullable = false)
    private LocalDateTime interviewTime;

    /** 面试形式：ONSITE（现场）/ VIDEO（视频）/ PHONE（电话） */
    @Column(name = "interview_type")
    private String interviewType; // ONSITE, VIDEO, PHONE

    /** 会议链接（或腾讯会议的「会议号+密码」拼接串） */
    @Column(name = "meeting_link", columnDefinition = "TEXT")
    private String meetingLink;

    /** 第几轮面试，默认 1（一面） */
    @Column(name = "round_number")
    private Integer roundNumber = 1;

    /** 面试官姓名 */
    private String interviewer;

    /** 备注 */
    @Column(columnDefinition = "TEXT")
    private String notes;

    /** 日程状态，默认 PENDING（待面试）。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InterviewStatus status = InterviewStatus.PENDING;

    /** 创建时间（updatable=false：创建后不再更新，保留原始创建时刻） */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间（每次修改自动刷新） */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 插入新行到数据库前，自动填充创建/更新时间。
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    /**
     * 更新数据库行时自动刷新更新时间。
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

