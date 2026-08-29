package com.ywy.interviewagentapplication.modules.knowledgebase.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 创建知识库面试请求：从知识库题库抽取题目开始一场面试。
 *
 * <h3>与 LLM 实时出题面试的区别</h3>
 * 普通面试（InterviewController）由 LLM 现场生成题目；
 * 知识库面试（本请求）从<b>预生成的题库</b>中抽取已启用的题目。题目经过人工审核，质量更可控，且创建面试不消耗 LLM 出题的 token 和时间。
 *
 * <h3>数量约束的设计依据</h3>
 * <ul>
 *   <li>mainQuestionCount：[1, 20]——太少无考察价值，太多面试过长</li>
 *   <li>followUpCount：[0, 5]——追问是深化考察，5 个是合理上限
 *       （每道主问题最多带 5 个追问，总题数可达 120）</li>
 * </ul>
 *
 * @param knowledgeBaseId  知识库 ID（题库来源）
 * @param category         面试方向（为空/null 则覆盖知识库内所有方向的启用题目）
 * @param difficulty       难度（为空则服务端用默认值 mid）
 * @param mainQuestionCount 主问题数量（1-20）
 * @param followUpCount    每题追问数量（0-5）
 * @param llmProvider      LLM 提供商（用于后续的答案评估，可空）
 */
public record CreateKnowledgeBaseInterviewRequest(
        @NotNull(message = "知识库不能为空")
        Long knowledgeBaseId,
        String category,
        String difficulty,
        @Min(value = 1, message = "主问题数量最少1题")
        @Max(value = 20, message = "主问题数量最多20题")
        int mainQuestionCount,
        @Min(value = 0, message = "追问数量不能小于0")
        @Max(value = 5, message = "每题追问最多5个")
        int followUpCount,
        String llmProvider
) {
}

