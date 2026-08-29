package com.ywy.interviewagentapplication.modules.knowledgebase.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 知识库查询请求：RAG 问答的输入。
 *
 * <h3>多知识库支持</h3>
 * 用户可以在一次提问中同时检索多个知识库（如同时查"Java 面试题库"和"系统设计资料"）。
 * 检索时对多个库的结果做合并和相似度排序。
 *
 * <h3>参数校验</h3>
 * <ul>
 *   <li>{@code @NotEmpty}：knowledgeBaseIds 至少包含一个知识库 ID</li>
 *   <li>{@code @NotBlank}：question 不能为空或纯空白</li>
 * </ul>
 * 校验注解在 Controller 层生效（@Valid 触发），不合法请求在进入 Service 之前就被拦截。
 *
 * <h3>单知识库查询的兼容构造器</h3>
 * 提供一个 {@code (Long, String)} 的便捷构造器，将单个知识库 ID 包装为列表。
 * 老版本客户端仍可传入单个 ID，无需修改。
 */
public record QueryRequest(
        @NotEmpty(message = "至少选择一个知识库")
        List<Long> knowledgeBaseIds,  // 支持多个知识库

        @NotBlank(message = "问题不能为空")
        String question
) {
    /**
     * 兼容单知识库查询
     */
    public QueryRequest(Long knowledgeBaseId, String question) {
        this(List.of(knowledgeBaseId), question);
    }
}


