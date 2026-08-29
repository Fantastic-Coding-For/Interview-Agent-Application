package com.ywy.interviewagentapplication.modules.knowledgebase.model;

import java.time.LocalDateTime;

/**
 * 问题生成任务的状态响应。
 *
 * @param skippedCount  跳过的重复题目数
 * @param savedCount    成功保存的题目数
 * @param questionGenTaskId  任务唯一标识（用于幂等和追溯）
 * @param questionGenConfig  本次任务的条件参数快照
 * @param message            结果摘要（如"已生成 10 道题，跳过 2 道重复题"）
 * @param error              失败原因（状态为 FAILED 时非 null）
 * @param updatedAt          状态最后更新时间
 */
public record QuestionGenStatusResponse(
        Long knowledgeBaseId,
        QuestionGenStatus questionGenStatus,
        String questionGenTaskId,
        QuestionGenerationConfig questionGenConfig,
        int savedCount,
        int skippedCount,
        String message,
        String error,
        LocalDateTime updatedAt
) {
}

