package com.ywy.interviewagentapplication.modules.interview.model;

import com.ywy.interviewagentapplication.common.model.AsyncTaskStatus;

import java.time.LocalDateTime;

/**
 * 面试历史列表项 DTO：用于简历详情页展示关联的面试记录。
 *
 * <h3>与 SessionListItemDTO 的区别</h3>
 * 本 DTO 用于<b>简历详情页</b>中展示"该简历关联的面试记录"，
 * 字段更精简（不含 skillId、difficulty、sourceType 等列表页专用字段）。
 * SessionListItemDTO 用于面试管理页的全量列表展示。
 *
 * <h3>为什么 evaluateStatus 存储为 String 而非枚举？</h3>
 * 历史列表项可能包含已废弃枚举，使用 String 比枚举更灵活，无需做枚举值不存在的兼容处理。
 * DTO 层的原则是 <b>宽进严出</b>：接受 null 和未知值，不能因一个字段的数据问题导致整个列表渲染失败。
 *
 * @param id             数据库主键
 * @param sessionId      会话唯一标识
 * @param totalQuestions  面试总题数
 * @param status         会话状态字符串（CREATED/IN_PROGRESS/COMPLETED/EVALUATED）
 * @param evaluateStatus 评估状态（PENDING/PROCESSING/COMPLETED/FAILED），可为 null
 * @param evaluateError  评估失败时的错误信息，可为 null
 * @param overallScore   整体评分（0-100），未评估时为 null
 * @param createdAt      创建时间
 * @param completedAt    完成时间，未完成时为 null
 */
public record InterviewHistoryItemDTO(
        Long id,
        String sessionId,
        Integer totalQuestions,
        String status,
        String evaluateStatus,
        String evaluateError,
        Integer overallScore,
        LocalDateTime createdAt,
        LocalDateTime completedAt
) {}

