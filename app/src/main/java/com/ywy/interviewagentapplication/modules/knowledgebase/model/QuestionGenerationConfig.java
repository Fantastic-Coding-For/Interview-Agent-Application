package com.ywy.interviewagentapplication.modules.knowledgebase.model;

/**
 * 知识库题目生成所需的条件参数快照。
 *
 * <h3>为什么需要"快照"？</h3>
 * 题目生成是异步任务，提交任务和实际执行之间存在时间差。
 * 将生成参数在<b>提交时刻</b>固化（questionGenConfig 转换为 JSON）：
 * <ul>
 *   <li>执行时使用提交时的参数</li>
 *   <li>状态查询接口能准确展示"这次任务用了什么参数"</li>
 *   <li>参数随任务持久化，应用重启后恢复任务时参数仍然完整</li>
 * </ul>
 *
 * <h3>安全性说明</h3>
 * 快照只包含业务参数（难度、数量等），不包含 Prompt、检索上下文或密钥。
 *
 * @param difficulty    难度等级（junior/mid/senior）
 * @param questionCount 主问题数量
 * @param followUpCount 每题追问数量
 * @param categoryLimit 生成方向数上限
 * @param llmProvider   LLM 提供商标识（null = 默认提供商）
 */
public record QuestionGenerationConfig(
        String difficulty,
        int questionCount,
        int followUpCount,
        int categoryLimit,
        String llmProvider
) {
}

