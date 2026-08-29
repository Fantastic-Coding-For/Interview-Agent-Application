package com.ywy.interviewagentapplication.modules.interview.model;

import java.util.List;

/**
 * 简历分析响应 DTO：LLM 对简历的分析结果。
 *
 * <h3>评分维度设计</h3>
 * 五个维度各有独立的满分值，总分 = 各维度得分之和：
 * <ul>
 *   <li>内容完整性（contentScore）：25 分——是否覆盖了必要信息</li>
 *   <li>结构清晰度（structureScore）：20 分——排版逻辑是否清晰</li>
 *   <li>技能匹配度（skillMatchScore）：25 分——技能是否匹配目标岗位</li>
 *   <li>表达专业性（expressionScore）：15 分——措辞是否专业准确</li>
 *   <li>项目经验（projectScore）：15 分——项目描述的 STAR 原则运用</li>
 * </ul>
 * 内容 + 技能各占 25 分（合计 50 分）——简历的核心价值在于"你有什么能力"，
 * 而非"你排版多好看"。结构 + 表达 + 项目 各 15-20 分是对呈现质量的考察。
 *
 * <h3>Suggestion 的优先级体系</h3>
 * 改进建议分为"高/中/低"三个优先级，前端据此展示不同颜色和排序：
 * 高优先级的建议（如"缺少联系方式"）应最醒目，低优先级的（如"可以加粗标题"）
 * 可折叠或后置。
 *
 * @param originalText 原始简历文本（用于前端展示"分析前/后"对比）
 */
public record ResumeAnalysisResponse(
        // 总分 (0-100)
        int overallScore,

        // 各维度评分
        ScoreDetail scoreDetail,

        // 简历摘要
        String summary,

        // 优点列表
        List<String> strengths,

        // 改进建议列表
        List<Suggestion> suggestions,

        // 原始简历文本
        String originalText
) {

    /**
     * 各维度评分详情
     */
    public record ScoreDetail(
            int contentScore,       // 内容完整性 (0-25)
            int structureScore,     // 结构清晰度 (0-20)
            int skillMatchScore,    // 技能匹配度 (0-25)
            int expressionScore,    // 表达专业性 (0-15)
            int projectScore        // 项目经验 (0-15)
    ) {}

    /**
     * 改进建议
     */
    public record Suggestion(
            String category,        // 建议类别：内容、格式、技能、项目等
            String priority,        // 优先级：高、中、低
            String issue,           // 问题描述
            String recommendation   // 具体建议
    ) {}
}

