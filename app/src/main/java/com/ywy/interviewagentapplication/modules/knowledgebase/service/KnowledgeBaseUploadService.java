package com.ywy.interviewagentapplication.modules.knowledgebase.service;

import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.infrastructure.file.FileHashService;
import com.ywy.interviewagentapplication.infrastructure.file.FileStorageService;
import com.ywy.interviewagentapplication.infrastructure.file.FileValidationService;
import com.ywy.interviewagentapplication.modules.knowledgebase.listener.VectorizeStreamProducer;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.VectorStatus;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Optional;

/**
 * 知识库上传服务：上传流程的编排者。
 *
 * <h3>关键设计决策</h3>
 * <ul>
 *   <li><b>内容级去重</b>：SHA-256 哈希在解析和存储<b>之前</b>计算：重复上传直接返回已有记录，省去解析（Tika 耗时）和存储（RustFS 空间）的开销</li>
 *   <li><b>向量化异步化</b>：上传请求只完成到"元数据入库 + 任务入队"，
 *       向量化（最耗时步骤）由后台消费者执行</li>
 *   <li><b>数据库状态驱动</b>：向量化状态（PENDING → PROCESSING → COMPLETED）
 *       记录在数据库，前端轮询该状态展示进度</li>
 * </ul>
 *
 * <h3>文件大小上限（50MB）</h3>
 * 50MB 是经验值：知识库文档通常远小于此。更大的文件会导致：
 * Tika 解析超时、Embedding API 调用次数过多、内存压力。
 * 如果业务需要更大的文件，应改为流式解析而非全量加载。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseUploadService {

    private final KnowledgeBaseParseService parseService;
    private final KnowledgeBasePersistenceService persistenceService;
    private final FileStorageService storageService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final FileValidationService fileValidationService;
    private final FileHashService fileHashService;
    private final VectorizeStreamProducer vectorizeStreamProducer;

    /** 文件大小上限（50MB） */
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB

    /**
     * 上传知识库文件。
     * <h4>上传完整流程编排（8 步）</h4>
     * <pre>
     * 1. 验证文件（大小 50MB、非空）
     * 2. 检测 MIME 类型（Tika 魔数检测）
     * 3. 验证文件类型（MIME 白名单 + 扩展名双重检查）
     * 4. 计算 SHA-256 哈希 → 去重检查
     * 5. 解析文本内容（Tika）
     * 6. 存储原始文件到 RustFS
     * 7. 保存元数据到数据库（状态 = PENDING）
     * 8. 发送向量化任务到 Redis Stream（异步处理）
     * </pre>
     * @param file     上传的文件
     * @param name     知识库名称（可选，为空则从文件名提取）
     * @param category 分类（可选）
     * @return 上传结果 Map（包含 knowledgeBase 元数据、storage 信息、duplicate 标记）
     * @throws BusinessException 如果文件验证失败、类型不支持、解析失败
     */
    public Map<String, Object> uploadKnowledgeBase(MultipartFile file, String name, String category) {
        // 1. 验证文件
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "知识库");

        String fileName = file.getOriginalFilename();
        log.info("收到知识库上传请求: {}, 大小: {} bytes, category: {}", fileName, file.getSize(), category);

        // 2. 验证文件类型
        String contentType = parseService.detectContentType(file);
        validateContentType(contentType, fileName);

        // 3. 检查知识库是否已存在（去重）
        String fileHash = fileHashService.calculateHash(file);
        Optional<KnowledgeBaseEntity> existingKb = knowledgeBaseRepository.findByFileHash(fileHash);
        if (existingKb.isPresent()) {
            log.info("检测到重复知识库: hash={}", fileHash);
            return persistenceService.handleDuplicateKnowledgeBase(existingKb.get(), fileHash);
        }

        // 4. 解析知识库文本（用于向量化）
        String content = parseService.parseContent(file);
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取文本内容，请确保文件格式正确");
        }

        // 5. 保存文件到RustFS
        String fileKey = storageService.uploadKnowledgeBase(file);
        String fileUrl = storageService.getFileUrl(fileKey);
        log.info("知识库已存储到RustFS: {}", fileKey);

        // 6. 保存知识库元数据到数据库（状态为 PENDING）
        KnowledgeBaseEntity savedKb = persistenceService.saveKnowledgeBase(file, name, category, fileKey, fileUrl, fileHash);

        // 7. 发送向量化任务到 Redis Stream（异步处理）
        vectorizeStreamProducer.sendVectorizeTask(savedKb.getId(), content);

        log.info("知识库上传完成，向量化任务已入队: {}, kbId={}", fileName, savedKb.getId());

        // 8. 返回结果（状态为 PENDING，前端可轮询获取最新状态）
        return Map.of(
                "knowledgeBase", Map.of(
                        "id", savedKb.getId(),
                        "name", savedKb.getName(),
                        "category", savedKb.getCategory() != null ? savedKb.getCategory() : "",
                        "fileSize", savedKb.getFileSize(),
                        "contentLength", content.length(),
                        "vectorStatus", VectorStatus.PENDING.name()
                ),
                "storage", Map.of(
                        "fileKey", fileKey,
                        "fileUrl", fileUrl
                ),
                "duplicate", false
        );
    }

    /**
     * 验证文件类型（MIME 白名单 + 扩展名双重检查）。
     * <p>
     * 双重检查的理由：某些文件（如 Markdown）的 MIME 类型不规范
     * （可能是 text/plain 或 text/markdown 或 octet-stream），
     * 通过扩展名兜底可以避免误拒绝。两种检查是 OR 关系，任一通过即可（白名单检查失败但扩展名合法时仍允许上传）。
     */
    private void validateContentType(String contentType, String fileName) {
        fileValidationService.validateContentType(
                contentType,
                fileName,
                fileValidationService::isKnowledgeBaseMimeType,
                fileValidationService::isMarkdownExtension,
                "不支持的文件类型: " + contentType + "，支持的类型：PDF、DOCX、DOC、TXT、MD等"
        );
    }

    /**
     * 重新向量化知识库（向量化失败后的手动恢复）。
     * <p>
     * <b>流程</b>：
     * <ol>
     *   <li>从 RustFS 重新下载原始文件并解析文本</li>
     *   <li>状态重置为 PENDING（清除旧错误信息）</li>
     *   <li>重新发送向量化任务到 Stream</li>
     * </ol>
     * 与首次上传的区别：跳过验证/存储/入库步骤，因为这些信息已存在。
     *
     * @param kbId 知识库 ID
     */
    public void revectorize(Long kbId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));

        log.info("开始重新向量化知识库: kbId={}, name={}", kbId, kb.getName());

        // 1. 下载文件并解析内容
        String content = parseService.downloadAndParseContent(kb.getStorageKey(), kb.getOriginalFilename());
        if (content == null || content.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法从文件中提取文本内容");
        }

        // 2. 更新状态为 PENDING（通过单独的 Service 保证事务生效）
        persistenceService.updateVectorStatusToPending(kbId);

        // 3. 发送向量化任务到 Stream
        vectorizeStreamProducer.sendVectorizeTask(kbId, content);

        log.info("重新向量化任务已发送: kbId={}", kbId);
    }
}

