package com.ywy.interviewagentapplication.modules.knowledgebase.service;

import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.VectorStatus;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 知识库持久化服务：知识库实体的数据库操作封装。
 *
 * <h3>职责边界</h3>
 * 本 Service 只处理<b>需要事务的数据库操作</b>：
 * <ul>
 *   <li>重复知识库的访问计数更新</li>
 *   <li>新知识库元数据的保存</li>
 *   <li>向量化状态的更新（PENDING 重置）</li>
 * </ul>
 * 与 UploadService 的分工：UploadService 编排流程（验证→解析→存储→入库→入队），
 * 本 Service 提供其中需要事务保证的原子操作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBasePersistenceService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    /**
     * 处理重复知识库上传（内容哈希相同）。
     * <p>
     * <b>重复上传的语义</b>：用户再次上传了一份内容完全相同的文件。
     * 不创建新记录，返回已存在的知识库信息，仅更新访问计数（"这份资料又被需要了一次"）。
     * <p>
     * <b>为什么返回 Map 而非强类型 DTO？</b>
     * 此方法的返回值被 UploadService 直接拼入更大的结果 Map 中。使用 Map 能保持灵活性。
     *
     * @param kb       已存在的知识库实体
     * @param fileHash 文件哈希（用于日志追踪）
     * @return 包含已有记录信息和 duplicate=true 标记的结果 Map
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> handleDuplicateKnowledgeBase(KnowledgeBaseEntity kb, String fileHash) {
        log.info("检测到重复知识库，返回已有记录: kbId={}", kb.getId());

        // 更新访问计数（在事务中）
        kb.incrementAccessCount();
        knowledgeBaseRepository.save(kb);

        // 重复知识库的向量数据应该已经存在，不需要重新向量化
        return Map.of(
                "knowledgeBase", Map.of(
                        "id", kb.getId(),
                        "name", kb.getName(),
                        "fileSize", kb.getFileSize(),
                        "contentLength", 0  // 不再存储 content，所以长度为 0
                ),
                "storage", Map.of(
                        "fileKey", kb.getStorageKey() != null ? kb.getStorageKey() : "",
                        "fileUrl", kb.getStorageUrl() != null ? kb.getStorageUrl() : ""
                ),
                "duplicate", true
        );
    }

    /**
     * 保存新知识库元数据到数据库。
     * <p>
     * <b>知识库名称提取策略</b>：用户指定的名称优先；
     * 未指定时从文件名提取（去除扩展名，"java-core.pdf" → "java-core"）。
     * <p>
     * <b>分类处理</b>：空白分类转为 null（与"未分类"语义一致，
     * 避免数据库中同时存在 "" 和 NULL 两种"未分类"表示）。
     *
     * @param file        上传的文件（用于获取文件名、大小、类型）
     * @param name        用户指定的知识库名称（可为 null）
     * @param category    分类（可为 null）
     * @param storageKey  RustFS 存储键
     * @param storageUrl  RustFS 访问 URL
     * @param fileHash    内容 SHA-256 哈希
     * @return 持久化后的实体（含生成的 ID）
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeBaseEntity saveKnowledgeBase(MultipartFile file, String name, String category,
                                                 String storageKey, String storageUrl, String fileHash) {
        try {
            KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
            kb.setFileHash(fileHash);
            kb.setName(name != null && !name.trim().isEmpty() ? name : extractNameFromFilename(file.getOriginalFilename()));
            kb.setCategory(category != null && !category.trim().isEmpty() ? category.trim() : null);
            kb.setOriginalFilename(file.getOriginalFilename());
            kb.setFileSize(file.getSize());
            kb.setContentType(file.getContentType());
            kb.setStorageKey(storageKey);
            kb.setStorageUrl(storageUrl);

            KnowledgeBaseEntity saved = knowledgeBaseRepository.save(kb);
            log.info("知识库已保存: id={}, name={}, category={}, hash={}", saved.getId(), saved.getName(), saved.getCategory(), fileHash);
            return saved;
        } catch (Exception e) {
            log.error("保存知识库失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存知识库失败");
        }
    }

    /**
     * 将知识库向量化状态重置为 PENDING。同时清除之前的错误信息，因为新任务开始时旧错误不再有意义。
     * <p>
     * 独立方法的原因：状态重置需要独立事务（避免与重新向量化的其他步骤共享事务导致异常回滚所有进度）。
     *
     * @param kbId 知识库 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateVectorStatusToPending(Long kbId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));

        kb.setVectorStatus(VectorStatus.PENDING);
        kb.setVectorError(null);
        knowledgeBaseRepository.save(kb);

        log.info("知识库向量化状态已更新为 PENDING: kbId={}", kbId);
    }

    /**
     * 从文件名提取知识库名称（去除扩展名）。
     * <p>
     * 边界处理：
     * <ul>
     *   <li>null/空 → "未命名知识库"（默认名称）</li>
     *   <li>"java-core.pdf" → "java-core"（lastIndexOf('.') 截断）</li>
     *   <li>".gitignore" → ".gitignore"（lastDot<=0 不截断，点开头的文件直接返回）</li>
     *   <li>"README" → "README"（lastDot<=0 不截断，无扩展名文件直接返回）</li>
     * </ul>
     */
    private String extractNameFromFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "未命名知识库";
        }
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            return filename.substring(0, lastDot);
        }
        return filename;
    }
}


