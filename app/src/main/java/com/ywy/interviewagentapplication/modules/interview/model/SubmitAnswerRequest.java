package com.ywy.interviewagentapplication.modules.interview.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 提交答案请求体。
 *
 * <h3>参数校验策略</h3>
 * 使用 Jakarta Bean Validation 注解在 Controller 层做入参校验，
 * 而非在 Service 层手动判空：
 * <ul>
 *   <li>{@code @NotBlank}：sessionId 和 answer 不能为 null 或空字符串。</li>
 *   <li>{@code @NotNull} + {@code @Min(0)}：questionIndex 不能为 null，且必须是 ≥0 的整数。
 *       问题索引从 0 开始，负数无意义。</li>
 * </ul>
 * 校验失败时 Spring 自动返回 400 Bad Request。
 *
 * @param sessionId     会话唯一标识（非空非 null）
 * @param questionIndex  当前回答的问题序号（非 null，从 0 开始，≥0）
 * @param answer         候选人的回答文本（非空非 null）
 */
public record SubmitAnswerRequest(
        @NotBlank(message = "会话ID不能为空")
        String sessionId,

        @NotNull(message = "问题索引不能为空")
        @Min(value = 0, message = "问题索引无效")
        Integer questionIndex,

        @NotBlank(message = "答案不能为空")
        String answer
) {}

