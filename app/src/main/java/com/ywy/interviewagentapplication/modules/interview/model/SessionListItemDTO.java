package com.ywy.interviewagentapplication.modules.interview.model;

import com.ywy.interviewagentapplication.common.model.AsyncTaskStatus;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewSessionEntity.SessionStatus;

import java.time.LocalDateTime;

/**
 * 面试会话列表项 DTO：面试管理页的轻量展示。
 *
 * <h3>设计原则：只包含列表页需要的字段</h3>
 * 与 {@link InterviewDetailDTO} 的区别：
 * <ul>
 *   <li>不含 questions（题目列表）——列表页不需要加载几百 KB 的题目数据</li>
 *   <li>不含 strengths/improvements——这些在详情页才展示</li>
 *   <li>包含了列表页特有的字段：skillId、difficulty、sourceType、evaluateStatus</li>
 * </ul>
 * 这种"轻量列表 DTO + 重量详情 DTO"的分层是后端性能优化的基础，列表查询只加载必要的列，详情查询才 JOIN 所有关联数据。
 *
 * <h3>静态工厂方法 from(Entity)</h3>
 * 使用静态工厂方法 {@link #from(InterviewSessionEntity)} 而非 MapStruct Mapper：
 * 列表 DTO 的字段映射简单直接（基本是 1:1），引入 Mapper 增加了不必要的抽象。
 * 只在映射逻辑复杂（如 JSON 解析、多源聚合）时才使用 Mapper，遵循"简单问题简单解决"的原则。
 *
 * @param sourceType      面试来源："NORMAL"（标准面试）或 "KNOWLEDGE_BASE"（知识库面试）
 * @param evaluateStatus  异步评估状态，null 表示尚未触发评估
 * @param overallScore    总分，null 表示尚未评估
 */
public record SessionListItemDTO(
        String sessionId,
        String skillId,
        String difficulty,
        Long resumeId,
        int totalQuestions,
        SessionStatus status,
        AsyncTaskStatus evaluateStatus,
        String evaluateError,
        Integer overallScore,
        String sourceType,
        Long knowledgeBaseId,
        String interviewCategory,
        LocalDateTime createdAt,
        LocalDateTime completedAt
) {
    /**
     * 从 JPA 实体创建列表项 DTO。
     * <p>
     * 对 totalQuestions 做了 null → 0 的安全转换，旧数据可能没有填充该字段。
     * 其他字段不做类似处理是因为：null 在 DTO 中是合法的（如 overallScore = null
     * 表示"未评估"，前端可以据此展示不同的 UI 状态）。
     *
     * @param e JPA 实体
     * @return 列表项 DTO
     */
    public static SessionListItemDTO from(InterviewSessionEntity e) {
        return new SessionListItemDTO(
                e.getSessionId(),
                e.getSkillId(),
                e.getDifficulty(),
                e.getResumeId(),
                e.getTotalQuestions() != null ? e.getTotalQuestions() : 0,
                e.getStatus(),
                e.getEvaluateStatus(),
                e.getEvaluateError(),
                e.getOverallScore(),
                e.getSourceType(),
                e.getKnowledgeBaseId(),
                e.getInterviewCategory(),
                e.getCreatedAt(),
                e.getCompletedAt()
        );
    }
}

