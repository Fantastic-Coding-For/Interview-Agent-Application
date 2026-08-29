package com.ywy.interviewagentapplication.modules.interview.model;

/**
 * 提交答案的响应体。
 *
 * <h3>字段语义</h3>
 * <ul>
 *   <li><b>hasNextQuestion</b>：是否有下一题可答。false 表示所有题目已答完，
 *       前端应引导用户进入"等待评估报告"页面。</li>
 *   <li><b>nextQuestion</b>：下一道题的内容（含追问）。hasNextQuestion 为 false 时为 null。
 *       前端拿到后立即渲染，用户无需手动点击"下一题"。</li>
 *   <li><b>currentIndex</b>：当前进度索引（也是已答完的题目数），用于前端显示"已回答 X/Y 题"。</li>
 *   <li><b>totalQuestions</b>：面试总题目数（含追问）。</li>
 * </ul>
 *
 * 将下一题嵌入同一响应中，保证"原子性"：提交答案 + 获取下一题是一个不可分割的操作。
 *
 * @param hasNextQuestion 是否还有下一题
 * @param nextQuestion    下一道题的 DTO（无下一题时为 null）
 * @param currentIndex    当前已回答的题数（即下一题的索引位置）
 * @param totalQuestions  面试总题数
 */
public record SubmitAnswerResponse(
        boolean hasNextQuestion,
        InterviewQuestionDTO nextQuestion,
        int currentIndex,
        int totalQuestions
) {}

