package com.ywy.interviewagentapplication.modules.resume.service;

import com.ywy.interviewagentapplication.common.config.AppConfigProperties;
import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.common.model.AsyncTaskStatus;
import com.ywy.interviewagentapplication.common.transaction.TransactionalExecutor;
import com.ywy.interviewagentapplication.infrastructure.file.FileStorageService;
import com.ywy.interviewagentapplication.infrastructure.file.FileValidationService;
import com.ywy.interviewagentapplication.modules.interview.model.ResumeAnalysisResponse;
import com.ywy.interviewagentapplication.modules.resume.listener.AnalyzeStreamConsumer;
import com.ywy.interviewagentapplication.modules.resume.listener.AnalyzeStreamProducer;
import com.ywy.interviewagentapplication.modules.resume.model.ResumeEntity;
import com.ywy.interviewagentapplication.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * <h1>简历上传服务</h1>
 *
 * <p>本服务负责简历上传流程的<b>完整编排</b>，职责包括：</p>
 * <ul>
 *   <li><b>文件校验</b>：大小限制（10MB）、类型白名单校验</li>
 *   <li><b>文本解析</b>：从 PDF/DOCX 等格式中提取纯文本（委托给 {@link ResumeParseService}）</li>
 *   <li><b>去重检测</b>：基于文件特征判断简历是否已存在，避免重复分析</li>
 *   <li><b>文件存储</b>：将原始文件上传到 RustFS（对象存储）</li>
 *   <li><b>持久化</b>：简历元数据和文本内容保存到数据库</li>
 *   <li><b>异步分析</b>：通过 Redis Stream 发送分析任务，<b>不等待分析结果</b></li>
 *   <li><b>重新分析</b>：支持手动触发重新分析（从数据库或文件重新获取文本）</li>
 * </ul>
 *
 * <h2>异步分析架构</h2>
 * <p>AI 分析简历是一个<b>耗时操作</b>（可能 10～60 秒），如果同步等待会导致 HTTP 请求超时。
 * 因此采用以下异步模式：</p>
 * <pre>
 *  上传请求 → 解析文本 → 保存数据库（status=PENDING）
 *           → 发送 Redis Stream 消息 → 立即返回 PENDING 状态
 *
 *  Consumer 线程 → 消费 Stream 消息 → AI 分析 → 更新状态（COMPLETED / FAILED）
 *
 *  前端轮询 → 查询分析状态 → 展示结果
 * </pre>
 *
 * <p><b>为什么用 Redis Stream 而不是 @Async 或线程池？</b></p>
 * <ul>
 *   <li><b>持久化</b>：Stream 消息持久化在 Redis 中，服务重启不会丢失任务</li>
 *   <li><b>消费者组</b>：支持多个消费者实例，自动负载均衡和故障转移</li>
 *   <li><b>ACK 机制</b>：处理完成后才确认，未确认的消息可被重新消费</li>
 *   <li><b>消息回溯</b>：Stream 保留历史消息，支持重试和审计</li>
 * </ul>
 *
 * @see AnalyzeStreamProducer 负责发送分析任务到 Redis Stream
 * @see AnalyzeStreamConsumer 负责消费 Stream 消息并执行 AI 分析
 * @see ResumeGradingService 负责实际的 AI 评分逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeUploadService {

    private final ResumeParseService parseService;
    private final FileStorageService storageService;
    private final ResumePersistenceService persistenceService;
    private final AppConfigProperties appConfig;
    private final FileValidationService fileValidationService;
    private final AnalyzeStreamProducer analyzeStreamProducer;
    private final ResumeRepository resumeRepository;
    private final TransactionalExecutor transactionalExecutor;

    /** 上传文件大小上限：10MB */
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    /**
     * <h3>上传并异步分析简历（主流程入口）</h3>
     *
     * <p>这是简历模块最核心的方法，编排了从文件上传到异步分析的完整流程。</p>
     *
     * <h4>处理流程（7 步）：</h4>
     * <ol>
     *   <li><b>文件校验</b>：大小（≤10MB）+ 类型白名单</li>
     *   <li><b>类型检测</b>：通过内容（而非扩展名）检测文件的真实 MIME 类型</li>
     *   <li><b>去重检查</b>：根据文件特征检查是否已有相同简历，
     *       如果已存在则直接返回历史分析结果</li>
     *   <li><b>文本解析</b>：从 PDF/DOCX 等格式提取纯文本，
     *       如果无法提取（如扫描版 PDF）则抛出异常</li>
     *   <li><b>文件存储</b>：上传原始文件到 RustFS 对象存储，获取访问 URL</li>
     *   <li><b>数据库保存</b>：将简历实体状态设为 PENDING</li>
     *   <li><b>异步分析</b>：发送分析消息到 Redis Stream，由消费者异步处理</li>
     * </ol>
     *
     * <p><b>性能考虑：</b>方法中对每个阶段都记录了耗时，通过日志输出便于排查性能瓶颈。</p>
     *
     * @param file Spring MVC 接收的上传文件（MultipartFile）
     * @return 包含简历基本信息、存储信息和重复标记的 Map
     * @throws BusinessException 文件校验失败或文本无法提取时抛出
     */
    public Map<String, Object> uploadAndAnalyze(org.springframework.web.multipart.MultipartFile file) {
        long startTime = System.currentTimeMillis();

        // 1. 验证文件大小
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "简历");

        String fileName = file.getOriginalFilename();
        long fileSize = file.getSize();
        log.info("收到简历上传请求: {}, 大小: {} bytes ({}), 上传开始处理",
                fileName, fileSize, formatFileSize(fileSize));

        // 2. 验证文件类型（基于内容的 MIME 检测）
        String contentType = parseService.detectContentType(file);
        validateContentType(contentType);

        // 3. 检查简历是否已存在（去重）
        Optional<ResumeEntity> existingResume = persistenceService.findExistingResume(file);
        if (existingResume.isPresent()) {
            log.info("简历上传处理完成（重复）: {} - 耗时: {}ms",
                    fileName, System.currentTimeMillis() - startTime);
            return handleDuplicateResume(existingResume.get());
        }

        // 4. 解析简历文本
        // 从 PDF/DOCX 等二进制格式中提取纯文本。扫描版 PDF（图片型）无法提取文字，此时抛出异常提示用户
        long parseStart = System.currentTimeMillis();
        String resumeText = parseService.parseResume(file);
        if (resumeText == null || resumeText.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法从文件中提取文本内容，请确保文件不是扫描版PDF");
        }
        log.info("简历文本解析完成: {} - 解析耗时: {}ms, 文本长度: {} 字符",
                fileName, System.currentTimeMillis() - parseStart, resumeText.length());

        // 5. 保存简历到 RustFS
        long storageStart = System.currentTimeMillis();
        String fileKey = storageService.uploadResume(file);
        String fileUrl = storageService.getFileUrl(fileKey);
        log.info("简历已存储到RustFS: {} - 存储耗时: {}ms",
                fileKey, System.currentTimeMillis() - storageStart);

        // 6. 保存简历到数据库（状态为 PENDING）
        ResumeEntity savedResume = persistenceService.saveResume(file, resumeText, fileKey, fileUrl);

        // 7. 发送分析任务到 Redis Stream（异步处理）
        analyzeStreamProducer.sendAnalyzeTask(savedResume.getId(), resumeText);

        long totalTime = System.currentTimeMillis() - startTime;
        log.info("简历上传处理完成: {}, resumeId={} - 总耗时: {}ms (解析+存储+入库)",
                fileName, savedResume.getId(), totalTime);

        // 8. 返回结果（状态为 PENDING，前端可轮询获取最新状态）
        return Map.of(
                "resume", Map.of(
                        "id", savedResume.getId(),
                        "filename", savedResume.getOriginalFilename(),
                        "analyzeStatus", AsyncTaskStatus.PENDING.name()
                ),
                "storage", Map.of(
                        "fileKey", fileKey,
                        "fileUrl", fileUrl,
                        "resumeId", savedResume.getId()
                ),
                "duplicate", false
        );
    }

    /**
     * <h3>格式化文件大小为可读字符串</h3>
     *
     * <p>转换规则：</p>
     * <ul>
     *   <li>&lt; 1KB → 以 B 为单位（如 "512B"）</li>
     *   <li>&lt; 1MB → 以 KB 为单位，保留 1 位小数（如 "256.5KB"）</li>
     *   <li>≥ 1MB → 以 MB 为单位，保留 1 位小数（如 "5.2MB"）</li>
     * </ul>
     *
     * @param bytes 文件大小（字节数）
     * @return 格式化后的可读字符串
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    /**
     * 验证文件类型是否在允许的白名单中。
     * 白名单从配置文件读取（{@code app.allowed-types}）。
     *
     * @param contentType 文件的 MIME 类型（基于内容检测，非扩展名）
     * @throws BusinessException 文件类型不在白名单中时抛出
     */
    private void validateContentType(String contentType) {
        fileValidationService.validateContentTypeByList(
                contentType,
                appConfig.getAllowedTypes(),
                "不支持的文件类型: " + contentType
        );
    }

    /**
     * <h3>处理重复简历</h3>
     *
     * <p>当检测到相同文件已上传过时调用此方法。根据历史分析状态返回不同的结果：</p>
     * <ul>
     *   <li><b>已有分析结果</b>：直接返回历史分析数据，前端直接展示</li>
     *   <li><b>无分析结果</b>（上次分析失败或尚未完成）：返回当前状态，
     *       前端通过轮询获取最新状态</li>
     * </ul>
     * @param resume 已存在的简历实体
     * @return 包含分析结果（或当前状态）的 Map，duplicate 标记为 true
     */
    private Map<String, Object> handleDuplicateResume(ResumeEntity resume) {
        log.info("检测到重复简历，返回历史分析结果: resumeId={}", resume.getId());

        // 根据简历 ID 获取历史分析结果
        Optional<ResumeAnalysisResponse> analysisOpt = persistenceService.getLatestAnalysisAsDTO(resume.getId());

        // 已有分析结果，直接返回。若没有分析结果（可能之前分析失败），返回当前状态
        return analysisOpt.map(resumeAnalysisResponse -> Map.of(
                "analysis", resumeAnalysisResponse,
                "storage", Map.of(
                        "fileKey", resume.getStorageKey() != null ? resume.getStorageKey() : "",
                        "fileUrl", resume.getStorageUrl() != null ? resume.getStorageUrl() : "",
                        "resumeId", resume.getId()
                ),
                "duplicate", true
        )).orElseGet(() -> Map.of(
                "resume", Map.of(
                        "id", resume.getId(),
                        "filename", resume.getOriginalFilename(),
                        "analyzeStatus", resume.getAnalyzeStatus() != null ? resume.getAnalyzeStatus().name() : AsyncTaskStatus.PENDING.name()
                ),
                "storage", Map.of(
                        "fileKey", resume.getStorageKey() != null ? resume.getStorageKey() : "",
                        "fileUrl", resume.getStorageUrl() != null ? resume.getStorageUrl() : "",
                        "resumeId", resume.getId()
                ),
                "duplicate", true
        ));
    }

    /**
     * <h3>重新分析简历</h3>
     *
     * <p>用于分析失败后的手动重试场景。与首次上传不同，此方法：</p>
     * <ul>
     *   <li>不从 MultipartFile 重新解析，而是使用数据库中<b>已缓存的简历</b></li>
     *   <li>如果缓存文本为空（旧数据未保存），则从 RustFS 重新下载文件并解析</li>
     *   <li>在<b>事务内</b>更新简历状态为 PENDING，清空之前的错误信息，设置简历文本内容，并最终保存到数据库</li>
     *   <li><b>事务提交后</b>再发送 Stream 消息（确保 Consumer 读取到已更新的状态）</li>
     * </ul>
     * @param resumeId 简历 ID
     * @throws BusinessException 简历不存在或无法获取文本内容时抛出
     */
    public void reanalyze(Long resumeId) {
        ResumeReanalyzeSource source = loadReanalyzeSource(resumeId);

        log.info("开始重新分析简历: resumeId={}, filename={}", resumeId, source.originalFilename());

        String resumeText = source.resumeText();
        // 是否存储了解析后的简历文本，存在为 false，不存在为 true
        boolean shouldCacheResumeText = !hasText(resumeText);
        if (shouldCacheResumeText) {
            // 如果指定 ID 的简历中没有存储解析后的简历文本（即解析失败），尝试从 RustFS 重新解析
            resumeText = parseService.downloadAndParseContent(
                    source.storageKey(), source.originalFilename());
            if (!hasText(resumeText)) { // 如果仍没有解析出简历，则抛出业务异常
                throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法获取简历文本内容");
            }
        }

        String taskContent = resumeText;
        transactionalExecutor.run(
                () -> updateResumeForReanalysis(resumeId, taskContent, shouldCacheResumeText));

        // 事务提交后再发送分析任务到 Stream
        analyzeStreamProducer.sendAnalyzeTask(resumeId, taskContent);

        log.info("重新分析任务已发送: resumeId={}", resumeId);
    }

    /**
     * 从数据库加载指定需要被重新分析的简历关键信息。
     *
     * @return 包含原始文件名、在 RustFs 中对应的 Key、缓存文本内容（可能为 null）的 Record
     */
    private ResumeReanalyzeSource loadReanalyzeSource(Long resumeId) {
        ResumeEntity resume = resumeRepository.findById(resumeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        return new ResumeReanalyzeSource(
                resume.getOriginalFilename(),
                resume.getStorageKey(),
                resume.getResumeText()
        );
    }

    /**
     * 更新指定 ID 的简历。
     *
     * <p>执行以下操作：</p>
     * <ul>
     *   <li>从数据库获取简历，如果 ID 不存在，抛出业务异常</li>
     *   <li>如果需要缓存简历文本到数据库，或数据库中本就没有存储简历文本，则设置指定的简历文本内容</li>
     *   <li>设置简历分析状态为 PENDING</li>
     *   <li>清空之前的错误信息</li>
     *   <li>重新保存到数据库</li>
     * </ul>
     *
     * @param resumeId              简历 ID
     * @param resumeText            简历文本内容
     * @param shouldCacheResumeText 是否需要缓存简历文本到数据库，true 表示需要
     */
    private void updateResumeForReanalysis(
            Long resumeId,
            String resumeText,
            boolean shouldCacheResumeText
    ) {
        ResumeEntity resume = resumeRepository.findById(resumeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));

        if (shouldCacheResumeText || !hasText(resume.getResumeText())) {
            resume.setResumeText(resumeText);
        }
        resume.setAnalyzeStatus(AsyncTaskStatus.PENDING);
        resume.setAnalyzeError(null);
        resumeRepository.save(resume);
    }

    /**
     * 判断字符串是否有实际文本内容（非 null 且非空串）。
     * @return true 表示存在实际文本内容
     */
    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * <h3>封装从数据库加载的简历原始信息</h3>
     * 使用 Java Record 保证不可变性，作为方法间传递数据的轻量载体。</p>
     *
     * @param originalFilename 简历原始文件名
     * @param storageKey       简历在 RustFS 中对应文件的 Key（用于重新下载）
     * @param resumeText       数据库中缓存的简历文本（可能为 null）
     */
    private record ResumeReanalyzeSource(
            String originalFilename,
            String storageKey,
            String resumeText
    ) {
    }
}

