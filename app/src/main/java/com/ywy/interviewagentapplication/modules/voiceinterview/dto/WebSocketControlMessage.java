package com.ywy.interviewagentapplication.modules.voiceinterview.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 前端经 WebSocket 发送的控制消息。
 *
 * <h3>协议设计</h3>
 * <ul>
 *   <li>type/action 两级路由：type 区分消息大类（control/audio/text），
 *       action 区分控制动作（start_phase / end_phase / end_interview / submit）</li>
 *   <li>data 是开放的 Map：不同 action 携带不同负载（如 submit 带 {"text": "..."}），
 *       避免为每种动作定义独立 DTO 导致类爆炸</li>
 *   <li><b>@JsonIgnoreProperties(ignoreUnknown = true)</b>：前端协议演进时（新增字段）
 *       旧版后端反序列化不报错，保证前后端可以独立发版</li>
 * </ul>
 *
 * <h3>为什么「手动提交」而非自动触发 LLM</h3>
 * 用户说完一段话后由前端点击「发送」（submit）才触发 LLM 管线——
 * 服务端 VAD 只能判断「一句话结束」，无法判断「候选人是否说完了全部内容」。
 * 手动提交把话语权交给用户，避免 VAD 切句导致的抢答。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSocketControlMessage {
    private String type; // "control"
    private String action; // "start_phase", "end_phase", "end_interview", "submit"
    private String phase; // "INTRO", "TECH", "PROJECT", "HR"
    private Map<String, Object> data;
}

