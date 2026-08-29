package com.ywy.interviewagentapplication.modules.knowledgebase.repository;

import com.ywy.interviewagentapplication.modules.knowledgebase.model.RagChatMessageEntity;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.RagChatMessageEntity.MessageType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * RAG 聊天消息 Repository。
 *
 * <h4>Pageable 的用法（findRecentCompletedBySessionId）</h4>
 * JPQL 查询本身不支持 LIMIT 子句，Spring Data JPA 通过 Pageable 参数
 * 在生成的 SQL 中追加 LIMIT/OFFSET。调用方传 {@code PageRequest.of(0, N)}。
 * <p>
 * 注意：ORDER BY messageOrder DESC 是为了让"最近的 N 条"在查询结果的前面，
 * 调用方拿到结果后通常需要反转为正序（时间顺序）再使用。
 */
@Repository
public interface RagChatMessageRepository extends JpaRepository<RagChatMessageEntity, Long> {

    /**
     * 获取会话的所有消息（按序号升序）。
     */
    List<RagChatMessageEntity> findBySessionIdOrderByMessageOrderAsc(Long sessionId);

    /**
     * 获取会话的最后一条消息
     */
    Optional<RagChatMessageEntity> findTopBySessionIdOrderByMessageOrderDesc(Long sessionId);

    /**
     * 获取会话中最近 N 条已完成的消息（按 messageOrder 降序取）
     */
    @Query("SELECT m FROM RagChatMessageEntity m WHERE m.session.id = :sessionId AND m.completed = true ORDER BY m.messageOrder DESC")
    List<RagChatMessageEntity> findRecentCompletedBySessionId(@Param("sessionId") Long sessionId, Pageable pageable);

    /**
     @Query("SELECT COUNT(m) FROM RagChatMessageEntity m WHERE m.session.id = :sessionId")
     Integer countBySessionId(@Param("sessionId") Long sessionId);

     /**
      * 查找未完成的消息（流式响应中断时清理用）
     */
    List<RagChatMessageEntity> findBySessionIdAndCompletedFalse(Long sessionId);

    /**
     * 删除会话的所有消息
     */
    void deleteBySessionId(Long sessionId);

    /**
     * 统计指定类型的消息数（如所有 USER 消息数 = 总提问次数）。
     */
    long countByType(MessageType type);
}


