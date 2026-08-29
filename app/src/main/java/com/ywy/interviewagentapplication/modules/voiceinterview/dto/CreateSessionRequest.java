package com.ywy.interviewagentapplication.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建语音面试会话的请求体。
 *
 * <h3>与实体默认值的差异</h3>
 * 注意本 DTO 的 builder 默认值与 {@code VoiceInterviewSessionEntity} 的默认值不完全一致
 * （例如 introEnabled 这里默认 false、实体默认 true）：
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateSessionRequest {
    /**
     * 向后兼容，新请求可不传（服务层会用 skillId 填充）
     */
    private String roleType;
    /**
     * 模板 ID，如 "java-backend", "bytedance-backend" 等
     */
    private String skillId;
    /**
     * "junior", "mid", "senior"
     */
    private String difficulty;
    private String customJdText;
    private Long resumeId;

    /**
     * 是否包含自我介绍（INTRO）环节。
     * 默认关闭：语音场景下自我介绍收益低，直接进入技术提问更能体现产品价值。
     */
    @Builder.Default
    private Boolean introEnabled = false;
    @Builder.Default
    private Boolean techEnabled = true;
    @Builder.Default
    private Boolean projectEnabled = true;
    @Builder.Default
    private Boolean hrEnabled = true;
    /** 计划时长（分钟），默认 30 分钟 */
    @Builder.Default
    private Integer plannedDuration = 30;

    /**
     * 指定 LLM 提供商（如 "dashscope"、"openai"）。null 时由系统按默认提供商路由。
     */
    private String llmProvider;
}

