package com.ywy.interviewagentapplication.modules.knowledgebase.model;

import java.time.LocalDateTime;

/**
 * 知识库详情 DTO：知识库管理页的展示数据。
 *
 * <h3>与实体的关系</h3>
 * 由 {@code KnowledgeBaseMapper} 从 {@link KnowledgeBaseEntity} 转换而来
 * （MapStruct 编译期生成转换代码）。
 * 与实体相比，DTO 不包含 storageKey/storageUrl/fileHash 等内部字段。
 *
 * <h3>字段选择原则</h3>
 * 列表项包含：
 * <ul>
 *   <li><b>展示字段</b>：name、category、originalFilename、fileSize、contentType</li>
 *   <li><b>统计字段</b>：accessCount、questionCount</li>
 *   <li><b>状态字段</b>：vectorStatus、questionGenStatus</li>
 * </ul>
 *
 * @param id                 知识库主键
 * @param name               知识库名称
 * @param category           分类（如 "Java面试"），null = 未分类
 * @param originalFilename   原始上传文件名
 * @param fileSize           文件大小（字节）
 * @param contentType        MIME 类型
 * @param uploadedAt         上传时间
 * @param lastAccessedAt     最后访问时间
 * @param accessCount        访问次数
 * @param questionCount      参与回答的次数
 * @param vectorStatus       向量化状态
 * @param vectorError        向量化错误信息（失败时非 null）
 * @param chunkCount         向量分块数量
 * @param questionGenStatus  问题生成状态
 * @param questionGenError   问题生成错误信息
 */
public record KnowledgeBaseListItemDTO(
        Long id,
        String name,
        String category,
        String originalFilename,
        Long fileSize,
        String contentType,
        LocalDateTime uploadedAt,
        LocalDateTime lastAccessedAt,
        Integer accessCount,
        Integer questionCount,
        VectorStatus vectorStatus,
        String vectorError,
        Integer chunkCount,
        QuestionGenStatus questionGenStatus,
        String questionGenError
) {
}


