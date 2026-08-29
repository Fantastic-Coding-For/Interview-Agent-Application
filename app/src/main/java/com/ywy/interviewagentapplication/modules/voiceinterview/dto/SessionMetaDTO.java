package com.ywy.interviewagentapplication.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话列表页的元数据 DTO。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>字段裁剪</b>：列表页只需要概览信息，不携带消息内容/评估详情等重数据，
 *       避免「查列表却把全部消息拉出来」的 N+1 与内存浪费。</li>
 *   <li><b>messageCount 是冗余统计字段</b>：由 Service 层逐会话 count 查询填充
 *       （{@code countDialogueMessages}），列表展示「共 N 轮对话」而无需加载消息实体。</li>
 *   <li><b>evaluateStatus 可为 null</b>：会话尚未结束时评估任务未创建，
 *       evaluateStatus 是 null——前端据此渲染「评估中/未评估/已出报告」等状态。</li>
 *   <li><b>evaluateError 带出来</b>：失败原因直接展示在列表（如「评估超时，请重新触发」），
 *       避免用户点进详情才发现失败。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionMetaDTO {
    private Long sessionId;
    private String roleType;
    private String status;
    private String currentPhase;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 实际面试时长（秒），结束时由 Service 计算并写入 */
    private Integer actualDuration;
    private Long messageCount;
    private String evaluateStatus;
    private String evaluateError;
}

