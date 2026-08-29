package com.ywy.interviewagentapplication.modules.knowledgebase.model;

/**
 * 知识库统计信息 DTO：管理面板的聚合数据。
 *
 * <h3>设计考量</h3>
 * 将多个指标聚合为一个 DTO 而非分多个接口调用，减少了网络往返管理面板的加载复杂度。
 * <p>
 * 注意：totalQuestionCount 是"用户总提问次数"，
 *
 * @param totalCount         知识库总数
 * @param totalQuestionCount 用户总提问次数（所有知识库 questionCount 之和）
 * @param totalAccessCount   所有知识库的总访问次数（所有知识库 accessCount 之和）
 * @param completedCount     已完成向量化的知识库数量
 * @param processingCount    向量化处理中的知识库数量
 */
public record KnowledgeBaseStatsDTO(
        long totalCount,
        long totalQuestionCount,
        long totalAccessCount,
        long completedCount,
        long processingCount
) {
}

