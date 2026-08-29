package com.ywy.interviewagentapplication.modules.knowledgebase.repository;

import com.ywy.interviewagentapplication.modules.knowledgebase.model.RagChatSessionEntity;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.RagChatSessionEntity.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * RAG 聊天会话 Repository。
 *
 * <h3>查询模式</h3>
 * <ul>
 *   <li><b>列表查询</b>：按更新时间/置顶状态排序——会话列表页</li>
 *   <li><b>关联查询</b>：按知识库查找会话——删除知识库时清理关联</li>
 *   <li><b>详情查询</b>：JOIN FETCH 避免 N+1 查询——会话详情页</li>
 * </ul>
 *
 * <h3>JOIN FETCH 与 DISTINCT 的组合</h3>
 * 详情查询使用 {@code LEFT JOIN FETCH s.knowledgeBases} 一次性加载关联的
 * 知识库集合，避免 N+1 查询（1 次会话查询 + N 次知识库查询）。
 * DISTINCT 的必要性：JOIN 会产生笛卡尔积。例如，一个会话关联 3 个知识库时，查询结果会有 3 行相同的会话数据，DISTINCT 消除重复。
 * <p>
 * 注意：messages 集合没有被 FETCH，它由另一个查询单独加载（RagChatMessageRepository.findBySessionIdOrderByMessageOrderAsc）。
 * 这是刻意的分离：消息可能非常多，与知识库分开加载更可控。
 */
@Repository
public interface RagChatSessionRepository extends JpaRepository<RagChatSessionEntity, Long> {

    /**
     * 按更新时间降序获取指定状态的所有会话（ACTIVE 或 ARCHIVED
     */
    List<RagChatSessionEntity> findByStatusOrderByUpdatedAtDesc(SessionStatus status);

    /**
     * 获取所有会话（按更新时间降序）
     */
    List<RagChatSessionEntity> findAllByOrderByUpdatedAtDesc();

    /**
     * 获取所有会话（按置顶状态和更新时间排序：置顶的在前，然后按更新时间降序）
     */
    @Query("SELECT s FROM RagChatSessionEntity s ORDER BY s.isPinned DESC, s.updatedAt DESC")
    List<RagChatSessionEntity> findAllOrderByPinnedAndUpdatedAtDesc();

    /**
     * 根据 ID 查找相关会话
     */
    @Query("SELECT DISTINCT s FROM RagChatSessionEntity s JOIN s.knowledgeBases kb WHERE kb.id IN :kbIds ORDER BY s.updatedAt DESC")
    List<RagChatSessionEntity> findByKnowledgeBaseIds(@Param("kbIds") List<Long> knowledgeBaseIds);

    /**
     * 根据 ID 查找相关会话详情（带消息列表和知识库）
     * 注意：通过 DISTINCT 避免笛卡尔积导致的重复数据
     */
    @Query("SELECT DISTINCT s FROM RagChatSessionEntity s LEFT JOIN FETCH s.knowledgeBases WHERE s.id = :id")
    Optional<RagChatSessionEntity> findByIdWithMessagesAndKnowledgeBases(@Param("id") Long id);

    /**
     * 根据 ID 查找相关会话详情（带知识库）
     */
    @Query("SELECT s FROM RagChatSessionEntity s LEFT JOIN FETCH s.knowledgeBases WHERE s.id = :id")
    Optional<RagChatSessionEntity> findByIdWithKnowledgeBases(@Param("id") Long id);
}

