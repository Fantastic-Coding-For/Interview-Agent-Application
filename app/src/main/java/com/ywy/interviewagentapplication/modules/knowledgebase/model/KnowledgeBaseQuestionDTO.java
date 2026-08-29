package com.ywy.interviewagentapplication.modules.knowledgebase.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库题目 DTO：题库管理页面的展示数据。
 *
 * <h3>与实体的对应关系</h3>
 * 由 {@code KnowledgeBaseQuestionService.toDTO} 从实体转换而来。
 * 转换过程中将 JSON 字符串字段（keyPointsJson、followUpsJson）
 * 反序列化为 List 结构。因为 DTO 面向前端展示，字段应该是结构化的而非 JSON 字符串。
 *
 * <h3>知识库题目的信息完备性</h3>
 * 与面试模块的 InterviewQuestionDTO 相比，知识库题目额外包含：
 * <ul>
 *   <li>scoringRubric（评分规则）：人工定义的评分标准，评估更客观</li>
 *   <li>sourceContext（来源上下文）：题目基于的知识库内容片段，可追溯</li>
 *   <li>status（审核状态）：题目生命周期管理</li>
 * </ul>
 * 这些字段让题库题目成为"自带评估标准"的高质量题目。
 *
 * @param knowledgeBaseName 所属知识库名称（冗余展示字段，避免前端二次查询）
 * @param skillId           固定为 "knowledge-base"（兼容面试会话表的非空约束）
 */
public record KnowledgeBaseQuestionDTO(
        Long id,
        Long knowledgeBaseId,
        String knowledgeBaseName,
        String skillId,
        String difficulty,
        String type,
        String category,
        String question,
        String topicSummary,
        String referenceAnswer,
        List<String> keyPoints,
        String scoringRubric,
        List<KnowledgeBaseQuestionFollowUpDTO> followUps,
        String sourceContext,
        KnowledgeBaseQuestionStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}

