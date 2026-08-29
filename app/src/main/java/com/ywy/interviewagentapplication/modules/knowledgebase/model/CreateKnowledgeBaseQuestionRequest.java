package com.ywy.interviewagentapplication.modules.knowledgebase.model;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 手动创建知识库题目请求：用户手动向题库添加题目。
 *
 * <h3>与 LLM 批量生成的区别</h3>
 * 批量生成（GenerateKnowledgeBaseQuestionsRequest）由 LLM 一次产出几十道题；
 * 手动创建用于：
 * <ul>
 *   <li>补充 LLM 遗漏的知识点</li>
 *   <li>添加领域专家整理的经典题目（质量高于 LLM 生成）</li>
 *   <li>修正 LLM 生成的题目（复制修改后另存）</li>
 * </ul>
 *
 * <h3>必填字段</h3>
 * category（方向）和 question（题干）是唯一必填项，它们定义了题目的核心身份。
 * 其他字段（参考答案、评分规则等）可选，评估阶段 LLM 可以基于知识库内容自行判断。
 *
 * @param status 初始状态（可空 = 默认 DRAFT，手动创建的题目同样需要审核流程）
 */
public record CreateKnowledgeBaseQuestionRequest(
        String difficulty,
        String type,
        @NotBlank(message = "面试方向不能为空")
        String category,
        @NotBlank(message = "题干不能为空")
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

