package com.ywy.interviewagentapplication.modules.knowledgebase.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG 聊天会话实体，一个多轮对话的容器。
 *
 * <h3>关系设计</h3>
 * <ul>
 *   <li><b>会话 ↔ 知识库（多对多）</b>：一个会话可以引用多个知识库作为检索源。
 *       使用中间表 {@code rag_session_knowledge_bases} 管理关联。
 *       使用 {@link Set} 而非 {@link List}：多对多关系中不应有重复关联，
 *       且 Set 的语义（无序、唯一）与多对多匹配。</li>
 *   <li><b>会话 ↔ 消息（一对多）</b>：{@code cascade = ALL + orphanRemoval = true}，
 *       删除会话时自动删除所有消息，消息不能脱离会话独立存在（孤儿删除）。</li>
 * </ul>
 *
 * <h3>messageCount 冗余字段</h3>
 * 消息数量直接存储在会话行中，避免每次展示列表时 COUNT 消息表。
 * 冗余的代价是需要在 addMessage 时同步维护——但换来列表查询性能的大幅提升
 * （一次会话查询即可展示全部列表信息）。
 *
 * <h3>@PostLoad 的兼容性处理</h3>
 * isPinned 字段是后期添加的，旧数据该字段为 NULL。
 * 每次从数据库加载时自动将 NULL 修正为 false，
 * 避免上层代码处理 null（并利用 JPA dirty checking 在下次更新时落库）。
 */
@Entity
@Table(name = "rag_chat_sessions", indexes = {
        @Index(name = "idx_rag_session_updated", columnList = "updatedAt")
})
@Getter
@Setter
@NoArgsConstructor
public class RagChatSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 会话标题（可自动生成或用户自定义）
     */
    @Column(nullable = false)
    private String title;

    /** 会话状态：ACTIVE（活跃，列表可见）或 ARCHIVED（已归档，列表隐藏） */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SessionStatus status = SessionStatus.ACTIVE;

    /**
     * 多对多：会话关联的知识库。
     * <p>
     * 为什么用 ManyToMany 而非一张知识库表加中间 Service？
     * 知识库与会话是典型的对等多对多关系，会话引用多个知识库、
     * 知识库被多个会话引用。JPA 的 ManyToMany + JoinTable 是标准解法。
     * <p>
     * ManyToMany 默认是 LAZY。使用 HashSet 保证唯一性（同一知识库不重复关联）。
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "rag_session_knowledge_bases",
            joinColumns = @JoinColumn(name = "session_id"),
            inverseJoinColumns = @JoinColumn(name = "knowledge_base_id")
    )
    private Set<KnowledgeBaseEntity> knowledgeBases = new HashSet<>();

    /**
     * 一对多：会话的消息列表。
     * <p>
     * {@code @OrderBy("messageOrder ASC")}：加载时按消息序号升序排列，对话按时间顺序展示，无需在 Service 层手动排序。
     * <p>
     * {@code cascade = ALL + orphanRemoval = true}：
     * 删除会话自动删除消息；从集合中移除的消息会在实体保存到数据库时被删除。
     */
    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("messageOrder ASC")
    private List<RagChatMessageEntity> messages = new ArrayList<>();

    /**
     * 创建时间（不可修改）
     * 会话列表按此字段降序排列，最近活跃的会话显示在最前面。
     */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 更新时间（最后一次消息时间）
     */
    private LocalDateTime updatedAt;

    /**
     * 消息数量（冗余字段，方便查询，避免每次列表查询时 COUNT 消息表）
     */
    private Integer messageCount = 0;

    /**
     * 是否置顶。
     * 置顶会话在列表中排在所有非置顶会话之前（见 Repository 的排序查询）。
     * 数据库默认 false，Java 端默认 false，双重保障。
     */
    @Column(columnDefinition = "boolean default false")
    private Boolean isPinned = false;

    public enum SessionStatus {
        /**
         * 活跃会话，列表中正常显示
         */
        ACTIVE,
        /**
         * 已归档，列表中隐藏（可在归档视图中查看）
         */
        ARCHIVED
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
     * 加载后回调：兼容旧数据的 null 值修复。
     * <p>
     * isPinned 字段是后加的，历史数据的该列为 NULL。
     * 每次加载时自动修正为 false，上层代码可以安全使用
     * {@code session.getIsPinned()} 而不做 null 检查。
     */
    @PostLoad
    protected void onLoad() {
        // 确保 isPinned 字段始终有值（兼容旧数据）
        if (isPinned == null) {
            isPinned = false;
        }
    }

    /**
     * 便捷方法：向会话添加消息。
     * <p>
     * 封装了三个联动操作：
     * <ol>
     *   <li>将消息加入 messages 集合</li>
     *   <li>设置消息的 session 反向引用（保持双向关联一致）</li>
     *   <li>同步更新 messageCount 和 updatedAt（维护冗余字段）</li>
     * </ol>
     * 调用方只需一行代码，不会遗漏任何联动更新。
     *
     * @param message 要添加的消息实体
     */
    public void addMessage(RagChatMessageEntity message) {
        messages.add(message);
        message.setSession(this);
        messageCount = messages.size();
        updatedAt = LocalDateTime.now();
    }

    /**
     * 便捷方法：获取关联知识库的 ID 列表。
     * <p>
     * @return 知识库 ID 列表
     */
    public List<Long> getKnowledgeBaseIds() {
        return knowledgeBases.stream()
                .map(KnowledgeBaseEntity::getId)
                .toList();
    }
}

