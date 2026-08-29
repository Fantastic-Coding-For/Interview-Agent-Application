package com.ywy.interviewagentapplication.modules.knowledgebase.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 知识库题目生成请求：触发 LLM 基于知识库内容批量生成面试题目。
 *
 * <h3>categoryLimit 参数的含义</h3>
 * LLM 生成题目时会自动归纳"面试方向"（如"整洁架构"、"Redis"、"测试策略"）。
 * categoryLimit 限制<b>方向数量</b>而非题目数量，避免 LLM 在 30 道题中
 * 分散到 15 个方向导致每个方向只有 1-2 道题（深度不足）。
 * 限制为 1-5 个方向，每个方向获得更集中的题目分布。
 *
 * @param difficulty    难度（junior/mid/senior，可空 = 默认 mid）
 * @param questionCount 生成的主问题数量（1-30）
 * @param followUpCount 每题追问数量（0-5，可空 = 默认 2）
 * @param categoryLimit 方向数上限（1-5，可空 = 默认 3）
 * @param llmProvider   LLM 提供商（可空 = 默认提供商）
 */
public record GenerateKnowledgeBaseQuestionsRequest(
        @Pattern(regexp = "junior|mid|senior", message = "题目难度不合法")
        String difficulty,
        @Min(value = 1, message = "题目数量最少1题")
        @Max(value = 30, message = "题目数量最多30题")
        int questionCount,
        @Min(value = 0, message = "追问数量不能小于0")
        @Max(value = 5, message = "每题追问最多5个")
        Integer followUpCount,
        @Min(value = 1, message = "方向数最少1个")
        @Max(value = 5, message = "方向数最多5个")
        @NotNull(message = "方向数量不能为空")
        Integer categoryLimit,
        @Size(max = 64, message = "模型提供商标识过长")
        String llmProvider
) {
}

