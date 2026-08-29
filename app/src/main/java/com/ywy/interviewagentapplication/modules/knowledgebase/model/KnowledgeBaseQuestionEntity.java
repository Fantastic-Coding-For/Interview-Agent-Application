package com.ywy.interviewagentapplication.modules.knowledgebase.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 知识库题目实体：从知识库内容生成的面试题库。
 *
 * <h3>与知识库的关系</h3>
 * 每道题目关联一个知识库（多对一）：题目是知识库内容的"面试化提炼"，知识库删除时题目应级联删除（题目失去内容基础就没有意义）。
 *
 * <h3>索引设计</h3>
 * <ul>
 *   <li><b>idx_kb_question_kb_status</b>：knowledge_base_id + status 联合索引——
 *       面试抽题的核心查询是 "某知识库下所有 ACTIVE 题目"</li>
 *   <li><b>idx_kb_question_skill_difficulty</b>：skill_id + difficulty——
 *       按难度筛选题目（skillId 固定为默认值）</li>
 * </ul>
 *
 * <h3>skillId 的兼容性设计</h3>
 * {@link #DEFAULT_SKILL_ID} 固定为 "knowledge-base"：
 * 知识库题目被抽取为面试问题时写入面试会话表（interview_sessions.skill_id
 * 有非空约束），此默认值保证了兼容性。skillId 本身已无业务含义。
 *
 */
@Entity
@Table(name = "knowledge_base_questions", indexes = {
        @Index(name = "idx_kb_question_kb_status", columnList = "knowledge_base_id,status"),
        @Index(name = "idx_kb_question_skill_difficulty", columnList = "skill_id,difficulty")
})
public class KnowledgeBaseQuestionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属知识库（多对一，LAZY）。
     * 创建题目时必须设置。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "knowledge_base_id", nullable = false)
    private KnowledgeBaseEntity knowledgeBase;

    /**
     * 知识库 ID。
     * <p>
     * 声明为 {@code insertable=false, updatable=false}，该列由
     * knowledgeBase 关联维护，此字段只是避免为取 ID 而触发懒加载的读取捷径。
     */
    @Column(name = "knowledge_base_id", insertable = false, updatable = false)
    private Long knowledgeBaseId;

    /**
     * 兼容旧面试会话表的 skill_id 非空约束，知识库题目固定写这个默认值
     */
    public static final String DEFAULT_SKILL_ID = "knowledge-base";

    @Column(name = "skill_id", nullable = false, length = 64)
    private String skillId = DEFAULT_SKILL_ID;

    /** 难度等级（junior/mid/senior） */
    @Column(length = 16)
    private String difficulty;

    /** 题目类型 */
    @Column(length = 64)
    private String type;

    /**
     * 面试方向，由 LLM 生成（例如"整洁架构"/"Redis"/"测试策略"）
     */
    @Column(length = 64)
    private String category;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    /** 知识点摘要 */
    @Column(length = 300)
    private String topicSummary;

    /** 参考答案（TEXT） */
    @Column(columnDefinition = "TEXT")
    private String referenceAnswer;

    /** 评分要点列表的 JSON 字符串 */
    @Column(columnDefinition = "TEXT")
    private String keyPointsJson;

    /** 评分规则（TEXT），人工定义的评分标准 */
    @Column(columnDefinition = "TEXT")
    private String scoringRubric;

    /** 追问列表的 JSON 字符串 */
    @Column(columnDefinition = "TEXT")
    private String followUpsJson;

    /** 题目来源上下文（基于的知识库内容片段） */
    @Column(columnDefinition = "TEXT")
    private String sourceContext;

    /**
     * 生成题目时的知识库内容哈希。
     * 用于检测题目是否过期（知识库内容更新后哈希变化）。
     */
    @Column(length = 64)
    private String kbContentHash;

    /** 题目审核状态。默认 DRAFT（LLM 生成的题目需人工审核后启用） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KnowledgeBaseQuestionStatus status = KnowledgeBaseQuestionStatus.DRAFT;

    /** 创建时间 */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 更新时间（每次编辑自动刷新） */
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public KnowledgeBaseEntity getKnowledgeBase() {
        return knowledgeBase;
    }

    public void setKnowledgeBase(KnowledgeBaseEntity knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public String getSkillId() {
        return skillId;
    }

    public void setSkillId(String skillId) {
        this.skillId = skillId;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getTopicSummary() {
        return topicSummary;
    }

    public void setTopicSummary(String topicSummary) {
        this.topicSummary = topicSummary;
    }

    public String getReferenceAnswer() {
        return referenceAnswer;
    }

    public void setReferenceAnswer(String referenceAnswer) {
        this.referenceAnswer = referenceAnswer;
    }

    public String getKeyPointsJson() {
        return keyPointsJson;
    }

    public void setKeyPointsJson(String keyPointsJson) {
        this.keyPointsJson = keyPointsJson;
    }

    public String getScoringRubric() {
        return scoringRubric;
    }

    public void setScoringRubric(String scoringRubric) {
        this.scoringRubric = scoringRubric;
    }

    public String getFollowUpsJson() {
        return followUpsJson;
    }

    public void setFollowUpsJson(String followUpsJson) {
        this.followUpsJson = followUpsJson;
    }

    public String getSourceContext() {
        return sourceContext;
    }

    public void setSourceContext(String sourceContext) {
        this.sourceContext = sourceContext;
    }

    public String getKbContentHash() {
        return kbContentHash;
    }

    public void setKbContentHash(String kbContentHash) {
        this.kbContentHash = kbContentHash;
    }

    public KnowledgeBaseQuestionStatus getStatus() {
        return status;
    }

    public void setStatus(KnowledgeBaseQuestionStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

