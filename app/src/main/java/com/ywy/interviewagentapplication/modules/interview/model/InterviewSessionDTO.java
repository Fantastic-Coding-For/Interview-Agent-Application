package com.ywy.interviewagentapplication.modules.interview.model;

import java.util.List;

/**
 * 面试会话DTO
 */
public record InterviewSessionDTO(
        String sessionId,
        String resumeText,
        int totalQuestions,
        int currentQuestionIndex,
        List<InterviewQuestionDTO> questions,
        SessionStatus status,
        Long knowledgeBaseId,
        String interviewCategory
) {
    public enum SessionStatus {
        /** 会话已创建 */
        CREATED,
        /** 面试进行中 */
        IN_PROGRESS,
        /** 面试已完成 */
        COMPLETED,
        /** 已生成评估报告 */
        EVALUATED
    }
}

