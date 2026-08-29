package com.ywy.interviewagentapplication.modules.voiceinterview.model;

/**
 * Voice Interview Session Status
 * 语音面试会话状态
 *
 * <h3>状态机</h3>
 * <pre>
 * IN_PROGRESS ──暂停(用户/超时)──▶ PAUSED ──恢复──▶ IN_PROGRESS
 *      │                              │
 *      ├──结束──▶ COMPLETED ◀─────────┘（恢复后正常结束）
 *      └──异常──▶ FAILED
 * </pre>
 * <ul>
 *   <li>暂停-恢复是<b>可逆循环</b>：PAUSED 只是「暂存状态」，恢复后回到 IN_PROGRESS 继续</li>
 *   <li>COMPLETED / FAILED 是<b>终态</b>：进入后不可再暂停/恢复/切换阶段</li>
 *   <li>FAILED：表示会话异常终止（如服务崩溃后的清理标记），
 *       IN_PROGRESS 长时间无活动会被定时任务兜底为 PAUSED（超时暂停）而非 FAILED</li>
 * </ul>
 *
 * <p>状态持久化在会话表，配合 WebSocket 断连兜底（{@code endSessionIfInProgress}）：
 * 任何进程内状态丢失（断连、崩溃）都能以数据库中的状态为准恢复。
 */
public enum VoiceInterviewSessionStatus {
    /**
     * WebSocket 已连接，面试进行中
     */
    IN_PROGRESS,

    /**
     * 用户主动暂停或超时自动暂停，状态已存库
     */
    PAUSED,

    /**
     * 已完成——面试正常结束
     */
    COMPLETED,

    /**
     * 失败——发生错误
     */
    FAILED
}

