package com.ywy.interviewagentapplication.modules.interviewschedule.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 面试邀约解析的请求体。
 *
 * <h3>source 参数的语义</h3>
 * source（feishu/tencent/zoom/其他）是<b>提示而非强制</b>：
 * <ul>
 *   <li>传了来源：解析服务优先按该平台模板匹配（更快更准）</li>
 *   <li>不传/传 null：自动探测（按文本特征关键词识别平台）</li>
 *   <li>传错也不致命：该平台规则匹配失败后仍会走 AI 兜底</li>
 * </ul>
 * 可空设计让前端无需强制用户选择来源（多数用户分不清平台名），
 * 自动探测承担默认体验。
 */
@Data
public class ParseRequest {
    /** 待解析的邀约原文（唯一必填项） */
    @NotBlank(message = "文本不能为空")
    private String rawText;

    /** 来源平台提示：feishu / tencent / zoom / other（可空，自动探测） */
    private String source; // feishu, tencent, zoom, other
}

