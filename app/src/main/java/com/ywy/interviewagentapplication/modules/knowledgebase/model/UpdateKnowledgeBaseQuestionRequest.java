package com.ywy.interviewagentapplication.modules.knowledgebase.model;

import java.util.List;

/**
 * 更新知识库题目请求：全部字段可选的部分更新（PATCH 语义）。
 *
 * <h3>null 的三重语义</h3>
 * <ul>
 *   <li><b>字段为 null</b>（JSON 中未出现该键）→ 不修改</li>
 *   <li><b>字段显式为 null</b>（JSON 中该键值为 null）→ 不修改
 *   <li><b>字符串字段为 ""</b>（JSON 中该键值为 ""）→ 不修改（Service 层 trimToNull 处理为 null，"宽进严出"）
 * </ul>
 */
public record UpdateKnowledgeBaseQuestionRequest(
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
        KnowledgeBaseQuestionStatus status
) {
}

