package com.ywy.interviewagentapplication.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 实时字幕消息（服务端推送给前端）。
 *
 * <h3>isFinal 的两层语义</h3>
 * <ul>
 *   <li><b>false（partial）</b>：ASR 的中间识别结果 / LLM 的流式增量文本。</li>
 *   <li><b>true（final）</b>：一句定稿文本，前端锁定展示并滚动到下一行</li>
 * </ul>
 * 双轨字幕（用户侧 + AI 侧）共用同一 DTO，前端据此区分如何渲染。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketSubtitleMessage {
    private String type; // "subtitle"
    private String text;
    private Boolean isFinal;
}

