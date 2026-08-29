package com.ywy.interviewagentapplication.modules.interviewschedule.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * 面试邀约解析的响应体。
 *
 * <h3>「成功与否 + 用了哪种方法」的透明设计</h3>
 * 解析是「尽力而为」的过程（规则解析可能只成功一半），因此响应
 * 不把失败当异常抛，而是：
 * <ul>
 *   <li><b>success</b>：是否解析出合格结果（三必填字段齐全）</li>
 *   <li><b>data</b>：解析结果（success=false 时为 null）</li>
 *   <li><b>confidence</b>：可信度（规则 0.95 / AI 0.8）——前端对低可信度
 *       结果可加「请人工确认」的提示，毕竟填错面试时间后果严重</li>
 *   <li><b>parseMethod</b>：实际生效的解析方式（rule/ai/none），
 *       用户可见的透明度：知道结果从哪来的</li>
 *   <li><b>log</b>：过程描述（成功/失败原因），直接展示给用户</li>
 * </ul>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ParseResponse {
    /** 是否解析成功 */
    private Boolean success;
    /** 解析出的结构化数据（失败为 null） */
    private CreateInterviewRequest data;
    /** 可信度（0.95 规则 / 0.8 AI / 0 失败） */
    private Double confidence;
    /** 解析方式：rule / AI / none */
    private String parseMethod;
    /** 过程日志/失败原因（用户可读） */
    private String log;
}

