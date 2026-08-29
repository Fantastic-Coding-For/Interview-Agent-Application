package com.ywy.interviewagentapplication.modules.interviewschedule.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 面试日程的展示 DTO。
 *
 * <p>与实体的差异仅在 JSON 序列化注解（interviewTime 固定输出
 * yyyy-MM-dd'T'HH:mm:ss 格式）——实体不含序列化关注点，
 * 保持 JPA 层的纯净；DTO 承载接口契约。
 *
 * <h3>为什么 DTO 与实体字段几乎一致还要分开</h3>
 * 当前二者同构，但演进方向不同：DTO 未来可能加「剩余天数」「格式化时间串」
 * 等展示字段，实体加查询字段——同构是现状，分层是防线。
 */
@Data
public class InterviewScheduleDTO {
    private Long id;
    private String companyName;
    private String position;

    /** 面试时间格式（JSON 输出固定为 ISO 风格格式，前端解析无需猜格式） */
    @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private java.time.LocalDateTime interviewTime;
    private String interviewType;
    private String meetingLink;
    private Integer roundNumber;
    private String interviewer;
    private String notes;
    private InterviewStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

