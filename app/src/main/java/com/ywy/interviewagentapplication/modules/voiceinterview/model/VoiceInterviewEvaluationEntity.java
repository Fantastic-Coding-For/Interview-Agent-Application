package com.ywy.interviewagentapplication.modules.voiceinterview.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 语音面试评估实体
 * <p>
 * Stores evaluation results in a format aligned with text-based interviews:
 * per-question evaluations, overall feedback, strengths, improvements, and reference answers.
 * All structured data (arrays/objects) is stored as JSON TEXT columns.
 * </p>
 *
 * <h3>与文本面试评估结果的同构设计</h3>
 * 语音面试复用了文本面试的评估结果模型：逐题评估、总评、亮点、改进点、参考回答——
 * 好处是<b>下游展示层与统计逻辑可以完全复用</b>，语音/文本两种面试形态只需
 * 在「把对话转成评估」的入口（评估 Prompt 与对话格式）做差异处理。
 *
 * <h3>JSON TEXT 列的设计权衡</h3>
 * 数组/对象类结构（如逐题评估列表）序列化为 JSON 字符串存储，而不是拆子表：
 * <ul>
 *   <li><b>优点</b>：写入原子（一条 INSERT 搞定）、读取零 JOIN、结构演进自由</li>
 *   <li><b>代价</b>：无法用 SQL 对列表内字段做条件查询/聚合——评估数据是「读整份展示」场景，
 *       没有按单题查询的需求，JSON 列是合适的取舍</li>
 * </ul>
 *
 * <h3>与 Session 的一对一关系</h3>
 * session_id 加唯一约束：一个会话最多一份评估报告。评估是幂等任务的重试对象，
 * 唯一约束在数据库层兜底「重复评估产生重复报告」的边界。
 */
@Entity
@Table(name = "voice_interview_evaluations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceInterviewEvaluationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的会话ID。唯一约束保证一对一：一次面试只产出一份评估报告 */
    @Column(name = "session_id", unique = true)
    private Long sessionId;

    /** 总评分 */
    @Column(name = "overall_score")
    private Integer overallScore;

    /** 总体评价文本 */
    @Column(name = "overall_feedback", columnDefinition = "TEXT")
    private String overallFeedback;

    /** 逐题评估列表的 JSON 序列化（TEXT 列，非 JSONB——MySQL 兼容） */
    @Column(name = "question_evaluations_json", columnDefinition = "TEXT")
    private String questionEvaluationsJson;

    /** 候选人优点列表 JSON */
    @Column(name = "strengths_json", columnDefinition = "TEXT")
    private String strengthsJson;

    /** 待改进点列表 JSON */
    @Column(name = "improvements_json", columnDefinition = "TEXT")
    private String improvementsJson;

    /** 参考回答列表 JSON */
    @Column(name = "reference_answers_json", columnDefinition = "TEXT")
    private String referenceAnswersJson;

    /** 面试官角色（技能方向），评估报告的展示维度之一 */
    @Column(name = "interviewer_role")
    private String interviewerRole;

    /** 面试日期（评估报告的归档维度） */
    @Column(name = "interview_date")
    private LocalDateTime interviewDate;

    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 插入新行前自动填充创建时间。
     */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}

