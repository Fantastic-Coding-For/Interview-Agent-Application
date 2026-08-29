package com.ywy.interviewagentapplication.modules.knowledgebase.model;

import java.util.List;

/**
 * 知识库面试容量查询响应（在指定方向、难度和主问题数下的可用题目容量）。
 *
 * <h3>业务场景</h3>
 * 用户在创建知识库面试前，需要知道题库的"家底"：
 * <ul>
 *   <li>有哪些方向（category）可选、每个方向有多少道启用题目</li>
 *   <li>选择追问数量时，有多少题目满足"至少 N 个追问"的条件</li>
 * </ul>
 * 前端据此渲染选项，<b>禁用不可选的组合</b>（如追问 5 但只有 2 道题
 * 满足条件时，"追问5"选项置灰）——避免用户提交后被服务端拒绝。
 *
 * <h3>FollowUpOption.selectable 的判断逻辑</h3>
 * selectable = 可用题目数 ≥ 用户选择的主问题数——
 * 即"在该追问数量下，能够凑齐一场面试"。
 * 这是前端选项置灰的直接依据。
 */
public record KnowledgeBaseInterviewCapacityResponse(
        Long knowledgeBaseId,
        String category,
        String difficulty,
        int mainQuestionCount,
        /** 各方向及其可用题目数（按题目数降序） */
        List<CategoryOption> categories,
        /** 各追问数量档位及其可用性 */
        List<FollowUpOption> followUpOptions
) {

    /** 方向选项：方向名 + 该方向下可用的启用题目数 */
    public record CategoryOption(
            String category,
            int availableQuestionCount
    ) {
    }

    /**
     * 追问数量选项。
     * selectable = true 表示选择该追问数时能凑齐 mainQuestionCount 道主问题。
     * @param followUpCount 追问数量
     * @param availableQuestionCount 满足追问数量的主题目数
     */
    public record FollowUpOption(
            int followUpCount,
            int availableQuestionCount,
            boolean selectable
    ) {
    }
}

