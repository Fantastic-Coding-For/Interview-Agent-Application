package com.ywy.interviewagentapplication.modules.interviewschedule.model;

/**
 * 面试日程状态枚举。
 *
 * <h3>状态机</h3>
 * <pre>
 *            ┌─ 参加完成 ─▶ COMPLETED
 * PENDING ───┤
 *    （待面试）├─ 取消 ─────▶ CANCELLED
 *            └─ 改期 ─────▶ RESCHEDULED
 * </pre>
 * <ul>
 *   <li><b>PENDING</b>：默认状态（创建即待面试）</li>
 *   <li><b>COMPLETED / CANCELLED</b>：终态——完成后不再参与「即将到来」列表，
 *       取消的面试不会复活</li>
 *   <li><b>RESCHEDULED</b>：改期标记。</li>
 * </ul>
 *
 * <h3>自动流转</h3>
 * PENDING 且 interviewTime 已过期的记录，由 {@code ScheduleStatusUpdater}
 * 每小时批量置为 CANCELLED——「约了但没来」的面试在系统中不长期滞留。
 */
public enum InterviewStatus {
    PENDING,
    COMPLETED,
    CANCELLED,
    RESCHEDULED
}

