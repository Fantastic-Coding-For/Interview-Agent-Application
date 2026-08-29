package com.ywy.interviewagentapplication.modules.voiceinterview.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 语音面试消息实体：对话历史的最小单元。
 *
 * <h3>「先问后答」的两阶段写入模型</h3>
 * 实时语音管线的落库顺序是：AI 提问先落库（userRecognizedText=null），
 * 候选人回答到达后<b>回填到最近的未回答行</b>（见
 * {@code VoiceInterviewService#fillLatestUnansweredQuestion}），而不是追加新行。
 * 这样保证「一问一答」占同一行，sequenceNum 才能表达真实的轮次顺序，
 * 上下文压缩与历史格式化都依赖这个不变式。
 *
 * <h3>SUMMARY 特殊行：复用消息表存摘要</h3>
 * 上下文压缩产生的滚动摘要也存进本表（messageType=SUMMARY），
 * 通过<b>负的 sequenceNum</b> 同时编码两个信息：
 * <ul>
 *   <li>负值在升序排序时永远排在正常轮次之前（不干扰对话顺序）</li>
 *   <li>{@code -(sequenceNum + 1)} 还原出「已覆盖的轮次数 coveredTurns」，
 *       供增量摘要判断哪些轮次还没被摘要过</li>
 * </ul>
 * 这种「字段编码」避免了为摘要单建一张表，代价是类型语义不够直观（见常量注释）。
 */
@Entity
@Table(name = "voice_interview_messages")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceInterviewMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "message_type", nullable = false)
    private String messageType; // USER_SPEECH, AI_SPEECH, SYSTEM, SUMMARY

    /**
     * 对话摘要行类型。用于持久化上下文压缩产生的滚动摘要，
     * 其 sequenceNum 取负值以在排序时位于普通轮次之前，
     * 并通过 {@code -(sequenceNum + 1)} 计算已覆盖的轮次数（coveredTurns）。
     */
    public static final String MESSAGE_TYPE_SUMMARY = "SUMMARY";

    /** 消息产生时所在的面试阶段 */
    @Column(name = "phase")
    @Enumerated(EnumType.STRING)
    private VoiceInterviewSessionEntity.InterviewPhase phase;

    /** 用户语音识别后的文本（候选人回答）。AI 提问行初始为 null，回答到达后回填 */
    @Column(name = "user_recognized_text", columnDefinition = "TEXT")
    private String userRecognizedText;

    /** AI 生成并播报的文本（面试官提问）。SUMMARY 行复用此列存摘要正文 */
    @Column(name = "ai_generated_text", columnDefinition = "TEXT")
    private String aiGeneratedText;

    /** 消息更新时间戳（说话/生成时间，与 createdAt 语义不同：业务时间 vs 落库时间） */
    @Column(name = "timestamp")
    private LocalDateTime timestamp;

    /** 轮次序号，升序 = 对话顺序。SUMMARY 行为负值 */
    @Column(name = "sequence_num")
    private Integer sequenceNum;

    /** 会话创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 插入新行到数据库前自动填充会话创建时间与业务时间戳。
     */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.timestamp = LocalDateTime.now();
    }

    /**
     * 文本归一化的工具：空串/ null 直接返回 null，否则 trim 后返回。
     *
     * <p>统一「空白文本 = 无文本」的语义，避免调用方到处写
     * {@code text == null || text.isBlank()} 判断；null 也是回填逻辑
     * 「尚未回答」的判定依据。
     */
    public static String trimToNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.trim();
    }
}

