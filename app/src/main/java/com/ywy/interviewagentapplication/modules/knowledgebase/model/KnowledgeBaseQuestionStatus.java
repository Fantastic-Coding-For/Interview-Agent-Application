package com.ywy.interviewagentapplication.modules.knowledgebase.model;

/**
 * 知识库题目的生命周期状态。
 *
 * <h3>状态机</h3>
 * <pre>
 * 创建 ──→ DRAFT ──→ ACTIVE ──→ ARCHIVED
 *                │         └──→ STALE
 *                └──→ STALE
 * <h3>为什么 DRAFT 是默认状态？</h3>
 * LLM 生成的题目质量不稳定（可能题意不清、答案有误）。
 * "生成 → 审核 → 启用"的三步流程保证进入面试的题目都经过人工把关，这是 AI 生成内容的质量控制机制。
 */
public enum KnowledgeBaseQuestionStatus {
    /** 未经过人工审核的题目 */
    DRAFT,
    /** 已审核通过的题目 */
    ACTIVE,
    /** 归档的题目 */
    ARCHIVED,
    /** 过时的题目 */
    STALE
}

