package com.ywy.interviewagentapplication.modules.knowledgebase.model;

/**
 * 知识库查询响应：RAG 问答的结果。
 * <p>
 * 展示来源有两个作用：
 * <ul>
 *   <li>增强可信度——用户知道这不是 LLM 凭空编造的</li>
 *   <li>便于追溯——回答有疑问时用户可以定位到具体的知识库文档</li>
 * </ul>
 */
public record QueryResponse(
        /** LLM 生成的回答 */
        String answer,
        /** 回答依据的知识库 ID（多库综合回答时为 null） */
        Long knowledgeBaseId,
        /** 对应知识库的名称 */
        String knowledgeBaseName
) {}


