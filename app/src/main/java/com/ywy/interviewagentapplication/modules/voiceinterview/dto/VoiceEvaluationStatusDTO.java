package com.ywy.interviewagentapplication.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 语音面试评估状态响应 DTO。
 *
 * <h3>「状态 + 可选结果」的组合设计</h3>
 * 轮询协议的核心：同一个 DTO 承担两种响应形态——
 * <ul>
 *   <li><b>评估中</b>（PENDING/PROCESSING/FAILED）：只有状态三件套，
 *       evaluation 为 null</li>
 *   <li><b>已完成</b>（COMPLETED）：附带完整评估详情 evaluation</li>
 * </ul>
 * <h3>evaluateStatusUpdatedAt 的用途</h3>
 * 前端可展示「评估于 X 时间完成/更新」，也在卡死场景下帮助判断
 * 「状态是不是一直没动过」（配合后端的 PENDING/PROCESSING 超时清理机制）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceEvaluationStatusDTO {

    /**
     * 评估任务状态字符串。
     * 会话未触发评估时为 null。
     */
    private String evaluateStatus;

    /**
     * FAILED 时的错误信息（如「评估超时，请重新触发」）。
     */
    private String evaluateError;

    /**
     * 当前评估状态最近一次更新的时间。
     */
    private LocalDateTime evaluateStatusUpdatedAt;

    /**
     * 语音面试评估详情 DTO，仅当状态为 COMPLETED 时非 null。
     */
    private VoiceEvaluationDetailDTO evaluation;
}
