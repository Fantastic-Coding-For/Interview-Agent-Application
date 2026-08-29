package com.ywy.interviewagentapplication.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话创建/恢复后的响应 DTO。
 *
 * <h3>关键字段：webSocketUrl</h3>
 * 服务端在响应中直接给出 WebSocket 连接地址（含会话ID），前端拿到即可建立连接，
 * 把「REST 创建会话 → WebSocket 建立通话」的两步流程封装成一次调用完成。
 * 注意当前实现硬编码 {@code ws://localhost:8080}（见
 * {@code VoiceInterviewService#buildSessionResponse}）——仅适合本地联调，
 * 生产环境需按部署域名/协议（wss）动态拼接。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionResponseDTO {
    private Long sessionId;
    private String roleType;
    private String currentPhase;
    private String status;
    private LocalDateTime startTime;
    private Integer plannedDuration;
    private String webSocketUrl;
}

