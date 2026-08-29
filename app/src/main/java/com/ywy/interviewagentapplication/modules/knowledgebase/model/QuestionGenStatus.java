package com.ywy.interviewagentapplication.modules.knowledgebase.model;

/**
 * 知识库问题生成状态枚举。
 *
 * <h3>业务背景</h3>
 * 知识库文档除了用于 RAG 检索外，还可以让 LLM 基于文档内容
 * <b>预生成面试题目</b>（如从 Java 面试题库文档中提取标准化题目）。
 * 这个枚举跟踪预生成任务的执行状态。
 *
 * <h3>状态机</h3>
 * <pre>
 *   NONE ──→ QUEUED ──→ PROCESSING ──→ COMPLETED
 *              │            │
 *              └────────────┴──→ FAILED ──→(重试)──→ QUEUED
 * </pre>
 * <ul>
 *   <li><b>NONE</b>：从未发起过问题生成（默认状态，大部分知识库停留在此状态）</li>
 *   <li><b>QUEUED</b>：任务已提交，等待执行</li>
 *   <li><b>PROCESSING</b>：LLM 正在生成题目</li>
 *   <li><b>COMPLETED</b>：生成成功，questionGenSavedCount 记录保存的题目数</li>
 *   <li><b>FAILED</b>：生成失败，questionGenError 记录原因</li>
 * </ul>
 *
 * <h3>与 VectorStatus 的关系</h3>
 * 问题生成依赖向量化完成（题目需要基于可检索的内容生成），
 * 但两者是独立的状态机，向量化成功不代表问题生成成功，反之亦然。独立跟踪使得每个流程都可以单独重试。
 */
public enum QuestionGenStatus {
    /** 未开始——默认状态 */
    NONE,
    /** 等待处理——任务已入队 */
    QUEUED,
    /** 问题生成中——LLM 正在执行 */
    PROCESSING,
    /** 问题生成成功 */
    COMPLETED,
    /** 问题生成失败 */
    FAILED
}

