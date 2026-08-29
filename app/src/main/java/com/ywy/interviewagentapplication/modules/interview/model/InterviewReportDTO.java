package com.ywy.interviewagentapplication.modules.interview.model;

import java.util.List;

/**
 * 面试评估报告 DTO：面试模块对外部暴露的评估结果。
 *
 * <h3>与 EvaluationReport 的关系</h3>
 * {@link com.ywy.interviewagentapplication.common.evaluation.EvaluationReport} 是通用评估层的数据结构（文字面试和语音面试共用），本 DTO 是面试模块专用的。
 * AnswerEvaluationService 负责将通用 EvaluationReport 转换为 InterviewReportDTO。
 * 两者字段高度相似但独立定义：遵循"各层使用各层的 DTO"原则，避免面试模块直接耦合评估模块的数据结构。
 *
 * @see com.ywy.interviewagentapplication.common.evaluation.EvaluationReport 通用评估报告
 */
public record InterviewReportDTO(
        String sessionId,
        int totalQuestions,
        int overallScore,                          // 总分 (0-100)
        List<CategoryScore> categoryScores,        // 各类别得分
        List<QuestionEvaluation> questionDetails,  // 每题详情
        String overallFeedback,                    // 总体评价
        List<String> strengths,                    // 优势
        List<String> improvements,                 // 改进建议
        List<ReferenceAnswer> referenceAnswers     // 参考答案
) {
    /**
     * 类别得分
     */
    public record CategoryScore(
            String category,
            int score,
            int questionCount
    ) {}

    /**
     * 问题评估详情
     */
    public record QuestionEvaluation(
            int questionIndex,
            String question,
            String category,
            String userAnswer,
            int score,
            String feedback
    ) {}

    /**
     * 参考答案
     */
    public record ReferenceAnswer(
            int questionIndex,
            String question,
            String referenceAnswer,
            List<String> keyPoints
    ) {}
}

