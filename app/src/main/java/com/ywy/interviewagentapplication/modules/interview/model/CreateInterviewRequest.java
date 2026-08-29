package com.ywy.interviewagentapplication.modules.interview.model;

import com.ywy.interviewagentapplication.modules.interview.skill.InterviewSkillService.CategoryDTO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 创建/更新面试日程的请求体。
 * <p>
 * 更新接口复用本类（见 {@code InterviewScheduleService#update} 的
 *
 * BeanUtils.copyProperties 白名单拷贝）。
 * @param resumeText 简历文本内容（可选，无简历时为通用面试）
 * @param questionCount 面试题目数量 (3-20)
 * @param resumeId  简历ID（可选，无简历时不传）
 * @param forceCreate   是否强制创建新会话（忽略未完成的会话），默认为 false
 * @param llmProvider   LLM提供商
 * @param skillId   面试主题 ID（如 java-backend, frontend, custom 等）
 * @param difficulty    难度级别: junior / mid / senior
 * @param customCategories  自定义面试的分类（JD 解析结果）
 * @param jdText    JD 原文（自定义面试时作为出题依据）
 * @param requestId 创建请求幂等键，刷新/重试时复用同一会话
 */
public record CreateInterviewRequest(
        String resumeText,

        @Min(value = 3, message = "题目数量最少3题")
        @Max(value = 20, message = "题目数量最多20题")
        int questionCount,

        Long resumeId,

        Boolean forceCreate,

        String llmProvider,

        @NotBlank(message = "面试主题不能为空")
        String skillId,

        String difficulty,

        List<CategoryDTO> customCategories,

        String jdText,

        String requestId
) {}

