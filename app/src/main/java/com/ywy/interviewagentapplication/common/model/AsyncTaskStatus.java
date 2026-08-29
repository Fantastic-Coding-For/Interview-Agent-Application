package com.ywy.interviewagentapplication.common.model;

/**
 * 全项目所有异步任务（向量化、分析、评估、题目生成）使用统一的状态模型。
 * 前端通过轮询 status 字段判断任务是否完成，从而避免为每种任务写不同的状态判断逻辑。
 * 前端轮询逻辑：if (status == COMPLETED || status == FAILED) → 停止轮询
 * 状态流转：
 *   PENDING（待处理） → PROCESSING（处理中） → COMPLETED（成功）
 *                        → FAILED（失败，含重试耗尽）
 */
public enum AsyncTaskStatus {
    /** 待处理——任务已入队但尚未被消费者领取 */
    PENDING,
    /** 处理中——消费者已领取并正在执行 */
    PROCESSING,
    /** 已完成——任务成功执行完毕 */
    COMPLETED,
    /** 失败——任务执行失败且重试次数已耗尽 */
    FAILED
}
