package com.ywy.interviewagentapplication.modules.knowledgebase.model;

import jakarta.validation.constraints.NotNull;

/**
 * 更新题目状态请求。
 * <p>
 * 独立于通用更新请求（UpdateKnowledgeBaseQuestionRequest）——
 * 状态切换是高频操作（审核题目时批量切换 DRAFT → ACTIVE），使用轻量请求体减少带宽和解析开销。
 * <p>
 * 状态切换的业务场景：
 * <ul>
 *   <li>DRAFT → ACTIVE：审核通过，题目进入面试池</li>
 *   <li>ACTIVE → ARCHIVED：题目过时，手动归档</li>
 *   <li>任意 → STALE：知识库内容更新触发批量标记</li>
 * </ul>
 *
 * @param status 目标状态（不能为 null）
 */
public record UpdateKnowledgeBaseQuestionStatusRequest(
        @NotNull(message = "题目状态不能为空")
        KnowledgeBaseQuestionStatus status
) {
}

