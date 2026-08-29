package com.ywy.interviewagentapplication.common.evaluation;

/**
 * 通用面试问答记录（文字面试和语音面试共用）。
 *
 * <h3>设计意图</h3>
 * 使用 Java {@code record} 而非普通 POJO 的原因：
 * <ul>
 *   <li><b>不可变性</b>：一条问答记录一旦产生就不应被修改，record 天然保证所有字段 final，
 *       避免了后续处理中意外篡改数据的风险。</li>
 *   <li><b>简洁性</b>：问答记录是纯数据载体，不包含业务逻辑，record 自动生成
 *       equals/hashCode/toString/构造器，消除了样板代码。</li>
 *   <li><b>跨模块传输</b>：作为 {@link UnifiedEvaluationService} 的输入参数，
 *       需要在线程间和模块间安全传递，不可变对象无需同步保护。</li>
 * </ul>
 *
 * @see UnifiedEvaluationService
 * @see EvaluationReport
 */
public record QaRecord(
        /**
         * 问题索引
         */
        int questionIndex,
        String question,
        /**
         * 问题类型
         */
        String category,
        /**
         * 用户答案
         */
        String userAnswer   // null 表示未回答
) {}

