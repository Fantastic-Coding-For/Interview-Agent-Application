package com.ywy.interviewagentapplication.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 语音面试消息的展示 DTO。
 *
 * <p>与 {@code VoiceInterviewMessageEntity} 的唯一差异：phase 从枚举拍平为 String——
 * 前端不依赖后端枚举命名，DTO 层做一次转换即可隔离「后端改枚举名、前端全炸」的耦合。
 * 转换逻辑见 {@code VoiceInterviewService#getConversationHistoryDTO}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceInterviewMessageDTO {
    private Long id;
    private Long sessionId;
    /** USER_SPEECH / AI_SPEECH / SYSTEM / DIALOGUE / SUMMARY 等消息类型 */
    private String messageType;
    private String phase;
    /** 用户语音识别后的文本（用户回答） */
    private String userRecognizedText;
    /** AI 生成并播报的文本（面试官提问/追问） */
    private String aiGeneratedText;
    private LocalDateTime timestamp;
    private Integer sequenceNum;
}

