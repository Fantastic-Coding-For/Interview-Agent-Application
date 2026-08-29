package com.ywy.interviewagentapplication.modules.voiceinterview.repository;

import com.ywy.interviewagentapplication.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 语音面试消息 Repository。
 *
 * <p>本接口显式定义的查询方法覆盖了对话系统的三类典型需求：
 * <ul>
 *   <li><b>顺序读取</b>：对话历史（升序）与「最近未回答行」回溯（降序）</li>
 *   <li><b>计数</b>：轮次统计（列表页 messageCount）与序号生成（max+1）</li>
 *   <li><b>整表清理</b>：删除会话时级联删除全部消息</li>
 * </ul>
 *
 * <h3>「排除 SUMMARY」的惯例</h3>
 * 上下文压缩的摘要行与对话消息混存一表（见
 * {@link VoiceInterviewMessageEntity} 的负 sequenceNum 设计），
 * 因此所有「对话轮次」语义的查询都必须显式 {@code MessageTypeNot(SUMMARY)}——
 * 否则摘要行会混进对话历史发给 LLM，或污染轮次计数。
 */
@Repository
public interface VoiceInterviewMessageRepository extends JpaRepository<VoiceInterviewMessageEntity, Long> {

    /**
     * 根据会话ID查找所有消息，按序号升序排列
     */
    List<VoiceInterviewMessageEntity> findBySessionIdOrderBySequenceNumAsc(Long sessionId);

    /**
     * 查找会话中除指定类型外的所有消息（升序）。
     */
    List<VoiceInterviewMessageEntity> findBySessionIdAndMessageTypeNotOrderBySequenceNumAsc(
            Long sessionId, String messageType);

    /**
     * 查找「最近的 AI 提问行（尚未回填用户回答）」。
     * 降序 + First 等价于「最近的未回答提问」。
     */
    Optional<VoiceInterviewMessageEntity>
    findFirstBySessionIdAndUserRecognizedTextIsNullAndAiGeneratedTextIsNotNullOrderBySequenceNumDesc(
            Long sessionId);

    /**
     * 统计会话全部消息数（含摘要行）。
     */
    long countBySessionId(Long sessionId);

    /**
     * 统计会话对话轮次数（排除摘要行），用于列表页展示与生成下一个序号。
     */
    long countBySessionIdAndMessageTypeNot(Long sessionId, String messageType);

    /**
     * 按会话批量删除消息（删除会话时的级联清理，替代实体级 cascade 以保持查询高效）。
     */
    void deleteBySessionId(Long sessionId);

    /**
     * 查找会话中指定类型的第一条消息（升序）。
     *
     * <p>用于读取上下文压缩的 SUMMARY 行（每会话最多一条，
     * 由 {@code saveSummaryRow} 的原地更新策略保证唯一性）。
     */
    Optional<VoiceInterviewMessageEntity> findFirstBySessionIdAndMessageTypeOrderBySequenceNumAsc(
            Long sessionId, String messageType);

}

