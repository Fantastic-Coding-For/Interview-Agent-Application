package com.ywy.interviewagentapplication.modules.knowledgebase.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * RAG 聊天消息实体：存储用户问题和 AI 回答。
 *
 * <h3>索引设计</h3>
 * <ul>
 *   <li><b>idx_rag_message_session</b>：session_id 列——按会话查询消息的基础索引</li>
 *   <li><b>idx_rag_message_order</b>：session_id + messageOrder 联合索引——
 *       按会话排序获取消息是最高频的查询模式（对话按顺序展示），
 *       联合索引使得"查某会话的全部消息并按顺序排列"只需一次索引扫描</li>
 * </ul>
 *
 * <h3>completed 字段的作用</h3>
 * 流式（SSE）响应场景下，AI 回答是逐 token 写入数据库的：
 * <ul>
 *   <li>流式开始：创建消息记录，completed = false</li>
 *   <li>流式过程中：每次刷新部分内容</li>
 *   <li>流式结束：completed = true</li>
 *   <li>流式中断（网络断开）：消息保持 completed = false，
 *       清理任务会删除这些未完成的消息</li>
 * </ul>
 * 默认值 true 是为了兼容非流式场景（一次性写入完整消息）。
 *
 * @see RagChatSessionEntity 所属会话
 */
@Entity
@Table(name = "rag_chat_messages", indexes = {
        @Index(name = "idx_rag_message_session", columnList = "session_id"),
        @Index(name = "idx_rag_message_order", columnList = "session_id, messageOrder")
})
@Getter
@Setter
@NoArgsConstructor
public class RagChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 关联的会话（多对一）。
     * <p>
     * 使用 LAZY 加载：查询消息列表时通常不需要会话信息，
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private RagChatSessionEntity session;

    /**
     * 消息类型：USER（用户问题）或 ASSISTANT（AI 回答）
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageType type;

    /** 消息内容（TEXT 类型，AI 回答可能非常长，远超 VARCHAR 上限） */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * 消息在会话内的顺序号（从 1 递增）。
     * 显式的序号字段而非依赖 id 排序：id 是全局的，删除/插入时可能不连续；
     * messageOrder 由应用层控制，保证会话内顺序稳定。
     */
    @Column(nullable = false)
    private Integer messageOrder;

    /**
     * 创建时间（创建后不可修改）
     */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 消息更新时间（用于流式响应更新）
     */
    private LocalDateTime updatedAt;

    /**
     * 消息是否完成（流式响应时使用）
     */
    private Boolean completed = true;

    public enum MessageType {
        /**
         * 用户消息
         */
        USER,
        /**
         * AI 回答
         */
        ASSISTANT
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 获取消息类型的小写字符串（方便分析）。
     * <p>
     * 例如：MessageType.USER → "user"，MessageType.ASSISTANT → "assistant"。
     */
    public String getTypeString() {
        return type.name().toLowerCase();
    }
}

