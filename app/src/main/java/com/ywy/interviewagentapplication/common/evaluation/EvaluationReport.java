package com.ywy.interviewagentapplication.common.evaluation;

import java.util.List;

/**
 * 通用面试评估报告（文字面试和语音面试共用）。
 * <h3>评分体系</h3>
 * 报告包含两层评分：
 * <ol>
 *   <li><b>整体评分</b>（overallScore）：所有已回答问题的平均分，用于快速评判</li>
 *   <li><b>分类评分</b>（categoryScores）：按技能维度（如项目经验、技术能力）分组统计，
 *       用于发现候选人的强弱项分布</li>
 * </ol>
 *
 * @see UnifiedEvaluationService 报告的生成者
 * @see QaRecord 评估的输入数据单元
 */
public record EvaluationReport(
        String sessionId,
        int totalQuestions,
        int overallScore,
        List<CategoryScore> categoryScores,
        List<QuestionEvaluation> questionDetails,
        String overallFeedback,
        List<String> strengths,
        List<String> improvements,
        List<ReferenceAnswer> referenceAnswers
) {
    public record CategoryScore(
            String category,
            int score,
            int questionCount
    ) {}

    public record QuestionEvaluation(
            int questionIndex,
            String question,
            String category,
            String userAnswer,
            int score,
            String feedback
    ) {}

    public record ReferenceAnswer(
            int questionIndex,
            String question,
            String referenceAnswer,
            List<String> keyPoints
    ) {}
}

