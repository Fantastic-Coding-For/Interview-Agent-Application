package com.ywy.interviewagentapplication.modules.knowledgebase.service;

import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.infrastructure.file.FileStorageService;
import com.ywy.interviewagentapplication.infrastructure.mapper.KnowledgeBaseMapper;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseStatsDTO;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.RagChatMessageEntity.MessageType;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.VectorStatus;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.KnowledgeBaseRepository;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.RagChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 知识库查询服务：知识库列表、详情、分类、搜索、统计、下载的查询入口。
 *
 * <h3>职责范围</h3>
 * 本 Service 是知识库模块的<b>读模型</b>（Read Model）：
 * <ul>
 *   <li>列表查询（支持状态过滤和多种排序）</li>
 *   <li>详情查询（单条记录）</li>
 *   <li>分类管理（列表、筛选、更新）</li>
 *   <li>关键词搜索</li>
 *   <li>统计聚合</li>
 *   <li>文件下载（含存储读取）</li>
 * </ul>
 * 写操作（上传、删除）由 UploadService/DeleteService 处理。
 * 读写分离的好处：查询逻辑可以独立优化（缓存、分页、索引），不干扰写入路径的事务设计。
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseListService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final RagChatMessageRepository ragChatMessageRepository;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final FileStorageService fileStorageService;

    /**
     * 获取知识库详情 DTO 列表（支持状态过滤，和按字段排序）。
     *
     * @param vectorStatus 向量化状态过滤，null 表示不过滤
     * @param sortBy       排序字段（size/access/question），null 或 "time" 表示按时间排序
     * @return 知识库详情 DTO 列表
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases(VectorStatus vectorStatus, String sortBy) {
        List<KnowledgeBaseEntity> entities;

        // 如果指定了状态，按状态过滤
        if (vectorStatus != null) {
            entities = knowledgeBaseRepository.findByVectorStatusOrderByUploadedAtDesc(vectorStatus);
        } else {
            // 否则获取所有知识库
            entities = knowledgeBaseRepository.findAllByOrderByUploadedAtDesc();
        }

        // 如果指定了排序字段，在内存中排序
        if (sortBy != null && !sortBy.isBlank() && !sortBy.equalsIgnoreCase("time")) {
            entities = sortEntities(entities, sortBy);
        }

        return knowledgeBaseMapper.toListItemDTOList(entities);
    }

    /** 获取所有知识库详情 DTO 列表（无参版本，既不按状态也不按排序字段） */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases() {
        return listKnowledgeBases(null, null);
    }

    /**
     * 按向量化状态获取知识库详情 DTO 列表（保持向后兼容）
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBasesByStatus(VectorStatus vectorStatus) {
        return listKnowledgeBases(vectorStatus, null);
    }

    /**
     * 根据 ID 获取知识库详情 DTO 列表
     */
    public Optional<KnowledgeBaseListItemDTO> getKnowledgeBase(Long id) {
        return knowledgeBaseRepository.findById(id)
                .map(knowledgeBaseMapper::toListItemDTO);
    }

    /**
     * 根据 ID 获取知识库实体
     */
    public Optional<KnowledgeBaseEntity> getKnowledgeBaseEntity(Long id) {
        return knowledgeBaseRepository.findById(id);
    }

    /**
     * 根据 ID 列表获取知识库名称列表。
     * <p>
     * 不存在的 ID 显示"未知知识库"而非抛异常：
     * 名称列表通常用于展示场景（如 RAG 会话中显示引用的知识库名称），数据不一致时展示占位符比报错更友好。
     */
    public List<String> getKnowledgeBaseNames(List<Long> ids) {
        return ids.stream()
                .map(id -> knowledgeBaseRepository.findById(id)
                        .map(KnowledgeBaseEntity::getName)
                        .orElse("未知知识库"))
                .toList();
    }

    // ========== 分类管理 ==========

    /** 获取所有分类（去重 + 排序，null 已排除） */
    public List<String> getAllCategories() {
        return knowledgeBaseRepository.findAllCategories();
    }

    /**
     * 根据分类获取知识库详情 DTO 列表，category 为 null/blank 时返回"未分类"列表
     */
    public List<KnowledgeBaseListItemDTO> listByCategory(String category) {
        List<KnowledgeBaseEntity> entities;
        if (category == null || category.isBlank()) {
            entities = knowledgeBaseRepository.findByCategoryIsNullOrderByUploadedAtDesc();
        } else {
            entities = knowledgeBaseRepository.findByCategoryOrderByUploadedAtDesc(category);
        }
        return knowledgeBaseMapper.toListItemDTOList(entities);
    }

    /**
     * 更新知识库分类。
     * <p>
     * category 若为空串则转为 null，统一"未分类"的表示
     */
    @Transactional
    public void updateCategory(Long id, String category) {
        KnowledgeBaseEntity entity = knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
        entity.setCategory(category != null && !category.isBlank() ? category : null);
        knowledgeBaseRepository.save(entity);
        log.info("更新知识库分类: id={}, category={}", id, category);
    }

    // ========== 搜索功能 ==========

    /**
     * 按关键词搜索知识库详情 DTO 列表（名称或文件名的模糊匹配）。
     * <p>
     * 空关键词直接返回全量列表（与列表接口行为一致：容错处理，搜索框清空时展示全部知识库）。
     * <p>
     * 注意：当前实现是 SQL LIKE 搜索（全表扫描 + 模糊匹配），
     * 数据量大时应考虑全文索引（MySQL FULLTEXT / PostgreSQL tsvector）
     * 或搜索引擎（Elasticsearch）。
     */
    public List<KnowledgeBaseListItemDTO> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return listKnowledgeBases();
        }
        return knowledgeBaseMapper.toListItemDTOList(
                knowledgeBaseRepository.searchByKeyword(keyword.trim())
        );
    }

    // ========== 排序功能 ==========

    /**
     * 按指定字段排序获取知识库详情 DTO 列表（保持向后兼容）
     * 仅支持 size/access/question 三种排序；其他值返回原列表（按时间）。
     */
    public List<KnowledgeBaseListItemDTO> listSorted(String sortBy) {
        return listKnowledgeBases(null, sortBy);
    }

    /**
     * 在内存中对实体列表排序（倒序，最大值在前）
     * 仅支持 size/access/question 三种排序；其他值返回原列表（按时间）。
     */
    private List<KnowledgeBaseEntity> sortEntities(List<KnowledgeBaseEntity> entities, String sortBy) {
        return switch (sortBy.toLowerCase()) {
            case "size" -> entities.stream()
                    .sorted((a, b) -> Long.compare(b.getFileSize(), a.getFileSize()))
                    .toList();
            case "access" -> entities.stream()
                    .sorted((a, b) -> Integer.compare(b.getAccessCount(), a.getAccessCount()))
                    .toList();
            case "question" -> entities.stream()
                    .sorted((a, b) -> Integer.compare(b.getQuestionCount(), a.getQuestionCount()))
                    .toList();
            default -> entities; // time 已经在数据库层面排序了
        };
    }

    // ========== 统计功能 ==========

    /**
     * 获取所有知识库的统计信息。
     * <p>
     * <b>totalQuestionCount 的统计口径</b>：
     * 使用 RagChatMessageRepository.countByType(USER)（用户消息数）
     * 而非 knowledgeBaseRepository.sumQuestionCount()（知识库参与回答次数之和）。
     * <p>两者的差异：一次关联多知识库提问会同时增加多个知识库的 questionCount，
     * 求和会重复计算；用户消息数才反映"真正的提问次数"。
     */
    public KnowledgeBaseStatsDTO getStatistics() {
        return new KnowledgeBaseStatsDTO(
                knowledgeBaseRepository.count(),
                ragChatMessageRepository.countByType(MessageType.USER),  // 真正的提问次数
                knowledgeBaseRepository.sumAccessCount(),
                knowledgeBaseRepository.countByVectorStatus(VectorStatus.COMPLETED),
                knowledgeBaseRepository.countByVectorStatus(VectorStatus.PROCESSING)
        );
    }

    // ========== 下载功能 ==========

    /**
     * 从 RustFS 下载知识库原始文件。
     * <p>
     * storageKey 为空时抛异常。理论上不应发生（上传时必填），但旧数据或异常写入可能缺失，防御性检查。
     *
     * @param id 知识库 ID
     * @return 文件字节数组
     */
    public byte[] downloadFile(Long id) {
        KnowledgeBaseEntity entity = knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));

        String storageKey = entity.getStorageKey();
        if (storageKey == null || storageKey.isBlank()) {
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件存储信息不存在");
        }

        log.info("下载知识库文件: id={}, filename={}", id, entity.getOriginalFilename());
        return fileStorageService.downloadFile(storageKey);
    }

    /**
     * 获取知识库实体（用于下载时读取文件名、Content-Type 等元数据）。
     */
    public KnowledgeBaseEntity getEntityForDownload(Long id) {
        return knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在"));
    }
}

