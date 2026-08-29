package com.ywy.interviewagentapplication.modules.interview.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试详情 DTO：面试记录详情页的完整数据聚合。
 *
 * <h3>设计考量</h3>
 * 这是面试模块中<b>字段最多</b>的 DTO，聚合了会话元数据 + 题目 + 优势/改进 + 参考答案 + 作答记录。
 * 为减少 Service 层的组装复杂度，使用 record 的紧凑语法一次性传入所有字段。
 *
 * @param evaluateStatus 评估状态（PENDING/PROCESSING/COMPLETED/FAILED），未评估时为 null
 * @param evaluateError  评估失败时的错误信息
 * @param sourceType     面试来源类型（NORMAL/KNOWLEDGE_BASE）
 * @param answers        答案详情列表（含已回答和未回答的所有题目）
 */
public record InterviewDetailDTO(
        Long id,
        String sessionId,
        Integer totalQuestions,
        String status,
        String evaluateStatus,
        String evaluateError,
        Integer overallScore,
        String sourceType,
        Long knowledgeBaseId,
        String overallFeedback,
        LocalDateTime createdAt,
        LocalDateTime completedAt,
        List<Object> questions,
        List<String> strengths,
        List<String> improvements,
        List<Object> referenceAnswers,
        List<AnswerDetailDTO> answers
) {
    /**
     * 单题评估明细（评估 + 参考回答的合并视图）。
     *
     * <h4>与 InterviewReportDTO.QuestionEvaluation 的区别</h4>
     * AnswerDetailDTO 侧重于"答题记录"（包含 answeredAt 时间戳），
     * QuestionEvaluation 侧重于"评估结果"（包含 score 和 feedback）。
     * 在详情页中，两者可能同时展示：AnswerDetailDTO 回答"候选人答了什么、什么时候答的"，
     * QuestionEvaluation 回答"答得怎么样"。
     *
     * @param userAnswer      候选人回答原文，未回答时为 null
     * @param referenceAnswer LLM 生成的参考答案
     * @param keyPoints       参考答案的关键要点列表
     * @param answeredAt      答题时间戳，未回答时为 null
     */
    public record AnswerDetailDTO(
            Integer questionIndex,
            String question,
            String category,
            String userAnswer,
            Integer score,
            String feedback,
            String referenceAnswer,
            List<String> keyPoints,
            LocalDateTime answeredAt
    ) {}
}
