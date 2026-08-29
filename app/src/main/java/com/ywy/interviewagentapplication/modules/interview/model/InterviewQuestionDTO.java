package com.ywy.interviewagentapplication.modules.interview.model;

import java.util.List;

/**
 * 面试问题 DTO。
 *
 * <h3>三阶段生命周期</h3>
 * <ol>
 *   <li><b>创建阶段</b>：通过 {@code create()} 或 {@code fromQuestionBank()} 创建，
 *       此时 userAnswer/score/feedback 均为 null</li>
 *   <li><b>答题阶段</b>：通过 {@code withAnswer()} 设置用户答案</li>
 *   <li><b>评估阶段</b>：通过 {@code withEvaluation()} 设置评分和反馈</li>
 * </ol>
 * 三个阶段使用不同的工厂/更新方法，使状态转换显式化。
 *
 * <h3>追问（Follow-up）机制</h3>
 * isFollowUp = true 表示该问题是某道主问题的追问。
 * parentQuestionIndex 指向主问题的索引。追问的 category 格式为
 * "{主问题分类}（追问N）"，如 "Java 核心（追问1）"。
 * 追问不单独计分，其评估结果合并到主问题中。
 *
 * @param questionIndex       题目序号（0-based），全局唯一
 * @param question            问题文本
 * @param type                Skill 分类维度 key（Skill category key，如 "MYSQL"、"JAVA_CORE"）
 * @param category            展示用的分类标签（如 "MySQL"、"Java 核心"）
 * @param topicSummary        知识点摘要（用于历史去重压缩）
 * @param userAnswer          用户回答文本（null = 未回答）
 * @param score               评分（null = 未评分）
 * @param feedback            评语（null = 未评估）
 * @param isFollowUp          是否为追问问题
 * @param parentQuestionIndex 追问的主问题索引（非追问时为 null）
 * @param referenceAnswer     LLM 生成的参考答案
 * @param keyPoints           参考答案的关键要点
 * @param scoringRubric       评分规则/标准
 * @param sourceContext       题目来源上下文（如知识库片段）
 */
public record InterviewQuestionDTO(
        int questionIndex,
        String question,
        String type,           // Skill category key，如 "MYSQL"、"CSS"、"DP"
        String category,       // 展示用标签，如 "MySQL"、"CSS"、"动态规划"
        String topicSummary,   // 知识点摘要，如 "Redis RDB/AOF 持久化对比"，用于历史去重压缩
        String userAnswer,
        Integer score,
        String feedback,
        boolean isFollowUp,
        Integer parentQuestionIndex,
        String referenceAnswer,
        List<String> keyPoints,
        String scoringRubric,
        String sourceContext
) {
    /**
     * 最简创建：仅指定序号、问题文本、类型、分类。
     * 用于 LLM 实时生成的题目（无预置参考答案/评分规则）。
     */
    public static InterviewQuestionDTO create(int index, String question, String type, String category) {
        return new InterviewQuestionDTO(
                index, question, type, category, null, null, null, null, false, null, null, null, null, null);
    }

    /**
     * 带追问信息的创建：指定 topicSummary、isFollowUp、parentQuestionIndex。
     * 用于 LLM 生成题目时同时创建主问题和追问。
     */
    public static InterviewQuestionDTO create(int index, String question, String type, String category,
                                              String topicSummary, boolean isFollowUp, Integer parentQuestionIndex) {
        return new InterviewQuestionDTO(
                index, question, type, category, topicSummary, null, null, null, isFollowUp, parentQuestionIndex,
                null, null, null, null);
    }

    /**
     * 从题库创建问题模板：携带完整的参考信息（参考答案、关键要点、评分规则、来源上下文）。
     * <p>
     * 与 LLM 实时生成的区别：题库中的题目有预定义的参考答案和评分标准，
     * 这使得评估时有客观依据而非全靠 LLM 主观判断。
     * sourceContext 记录了题目来源（如"来自 Java 核心知识库第3章"），方便追溯和调试。
     */
    public static InterviewQuestionDTO fromQuestionBank(int index, String question, String type,
                                                        String category, String topicSummary,
                                                        String referenceAnswer, List<String> keyPoints,
                                                        String scoringRubric, String sourceContext) {
        return new InterviewQuestionDTO(
                index, question, type, category, topicSummary, null, null, null, false, null,
                referenceAnswer, keyPoints, scoringRubric, sourceContext);
    }

    /**
     * 设置用户答案：返回新的 DTO 而非修改当前对象。
     * @param answer 用户回答文本
     * @return 包含答案的新 InterviewQuestionDTO
     */
    public InterviewQuestionDTO withAnswer(String answer) {
        return new InterviewQuestionDTO(
                questionIndex, question, type, category, topicSummary, answer, score, feedback,
                isFollowUp, parentQuestionIndex, referenceAnswer, keyPoints, scoringRubric, sourceContext);
    }

    /**
     * 设置评估结果（评分 + 反馈）：返回新的 DTO 而非修改当前对象。
     * @param score    评分 0-100
     * @param feedback 评语
     * @return 包含评估结果的新 InterviewQuestionDTO
     */
    public InterviewQuestionDTO withEvaluation(int score, String feedback) {
        return new InterviewQuestionDTO(
                questionIndex, question, type, category, topicSummary, userAnswer, score, feedback,
                isFollowUp, parentQuestionIndex, referenceAnswer, keyPoints, scoringRubric, sourceContext);
    }
}

