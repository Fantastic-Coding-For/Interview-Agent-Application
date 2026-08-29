package com.ywy.interviewagentapplication.modules.knowledgebase.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RAG 聊天相关的所有 DTO：请求体和响应体的聚合容器。
 *
 * <h3>为什么使用"容器类 + 嵌套 record"而非独立文件？</h3>
 * RAG 聊天的 DTO 数量多但每个都很小（8 个 record）：
 * <ul>
 *   <li><b>减少文件数量</b>：如果每个 DTO 一个文件，会产生 8 个零散文件；
 *       聚合在一个容器类中，IDE 导航和代码审查都更方便</li>
 *   <li><b>语义聚合</b>：这些 DTO 只服务于 RAG 聊天这一个功能域，
 *       聚合表达了"同属一个功能"的语义</li>
 *   <li><b>命名空间</b>：{@code RagChatDTO.CreateSessionRequest} 比
 *       独立文件中的 CreateSessionRequest 更明确</li>
 * </ul>
 *
 * <h3>请求 DTO vs 响应 DTO 的划分</h3>
 * 上半部分（CreateSessionRequest 等）是客户端 → 服务端的数据（入参校验），
 * 下半部分（SessionDTO 等）是服务端 → 客户端的数据（展示字段）。
 * 请求 DTO 使用校验注解（@NotEmpty/@NotBlank），响应 DTO 不做校验。
 */
public class RagChatDTO {

    // ========== 请求 DTO ==========

    /**
     * 创建会话请求
     */
    public record CreateSessionRequest(
            @NotEmpty(message = "至少选择一个知识库")
            List<Long> knowledgeBaseIds,

            String title  // 可选，为空则自动生成
    ) {}

    /**
     * 发送消息请求
     */
    public record SendMessageRequest(
            @NotBlank(message = "问题不能为空")
            String question
    ) {}

    /**
     * 更新标题请求
     */
    public record UpdateTitleRequest(
            @NotBlank(message = "标题不能为空")
            String title
    ) {}

    /**
     * 更新知识库请求
     */
    public record UpdateKnowledgeBasesRequest(
            @NotEmpty(message = "至少选择一个知识库")
            List<Long> knowledgeBaseIds
    ) {}

    // ========== 响应 DTO ==========

    /**
     * 会话基础信息
     */
    public record SessionDTO(
            Long id,
            String title,
            List<Long> knowledgeBaseIds,
            LocalDateTime createdAt
    ) {}

    /**
     * 会话列表项
     */
    public record SessionListItemDTO(
            Long id,
            String title,
            Integer messageCount,
            List<String> knowledgeBaseNames,
            LocalDateTime updatedAt,
            Boolean isPinned
    ) {}

    /**
     * 会话详情（含消息）
     */
    public record SessionDetailDTO(
            Long id,
            String title,
            List<KnowledgeBaseListItemDTO> knowledgeBases,
            List<MessageDTO> messages,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    /**
     * 消息 DTO
     */
    public record MessageDTO(
            Long id,
            String type,  // "user" | "assistant"
            String content,
            LocalDateTime createdAt
    ) {}
}

