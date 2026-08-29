package com.ywy.interviewagentapplication.modules.knowledgebase.model;

import java.util.List;

/**
 * 知识库题目的追问 DTO。
 * <p>
 * 追问是主问题的延伸问题，用于面试中考察候选人的深层理解
 * （如主问题"什么是 Redis 持久化"，追问"线上 RDB 持久化导致阻塞怎么办"）。
 * <p>
 * 每道主问题可以有多个追问，面试时从中随机抽取指定数量
 * （见 KnowledgeBaseInterviewService.pickFollowUps）。
 *
 * @param question        追问问题
 * @param referenceAnswer 追问的参考答案（评估时供 LLM 参考）
 * @param keyPoints       参考答案的关键要点
 * @param scoringRubric   追问的评分规则
 */
public record KnowledgeBaseQuestionFollowUpDTO(
        String question,
        String referenceAnswer,
        List<String> keyPoints,
        String scoringRubric
) {
    public KnowledgeBaseQuestionFollowUpDTO(String question) {
        this(question, null, List.of(), null);
    }
}

