package com.ywy.interviewagentapplication.modules.resume.service;

import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.infrastructure.file.FileStorageService;
import com.ywy.interviewagentapplication.modules.interview.service.InterviewPersistenceService;
import com.ywy.interviewagentapplication.modules.resume.model.ResumeEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * <h1>简历删除服务</h1>
 *
 * <p>负责编排简历删除的<b>完整流程</b>，协调多个服务按顺序清理关联数据。</p>
 *
 * <h2>删除顺序（严格有序）</h2>
 * <p>删除一个简历需要清理 3 个位置的数据，顺序很重要：</p>
 * <ol>
 *   <li><b>文件存储</b>（RustFS）：删除原始文件。
 *       此步骤失败<b>不中断</b>后续删除，因为文件存储不可用不应阻止数据库清理</li>
 *   <li><b>面试数据</b>（数据库）：删除所有面试会话及关联的面试答案。
 *       必须在简历实体删除前执行（因为存在外键依赖）</li>
 *   <li><b>简历数据</b>（数据库）：删除简历实体及其分析记录。
 *       委托给 {@link ResumePersistenceService#deleteResume} 处理</li>
 * </ol>
 *
 * <h2>为什么独立为单独的服务？</h2>
 * <ul>
 *   <li><b>单一职责</b>：上传（UploadService）和删除（DeleteService）是独立的业务能力</li>
 *   <li><b>编排复杂度</b>：删除涉及 3 个子服务的协调，独立出来避免 UploadService 臃肿</li>
 *   <li><b>失败隔离</b>：文件删除失败不阻塞数据库删除，这种策略差异值得显式表达</li>
 * </ul>
 *
 * @see ResumePersistenceService 数据库层面的删除操作
 * @see InterviewPersistenceService 面试会话删除
 * @see FileStorageService RustFS 文件删除
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeDeleteService {

    private final ResumePersistenceService persistenceService;
    private final InterviewPersistenceService interviewPersistenceService;
    private final FileStorageService storageService;

    /**
     * <h3>删除简历及其所有关联数据</h3>
     *
     * <p>按顺序执行以下清理操作：</p>
     * <ol>
     *   <li><b>查询数据库中简历</b>：校验简历是否存在（不存在直接抛异常）</li>
     *   <li><b>删除存储文件</b>：从 RustFS 删除原始文件。
     *       失败时仅记录 warn 日志，<b>继续执行</b>后续步骤，
     *       文件存储不可用不应阻止数据库清理，且存储服务可能有自动过期策略</li>
     *   <li><b>删除面试会话</b>：删除所有关联面试及其答案数据。
     *       必须在步骤 4 之前执行</li>
     *   <li><b>删除数据库记录</b>：删除简历实体及分析记录。
     *       委托 {@link ResumePersistenceService#deleteResume} 在事务内执行</li>
     * </ol>
     *
     * <p><b>失败策略总结：</b></p>
     * <table border="1">
     *   <tr><th>步骤</th><th>失败行为</th><th>原因</th></tr>
     *   <tr><td>文件删除</td><td>仅 warn，继续</td><td>存储服务不阻塞数据库清理</td></tr>
     *   <tr><td>面试删除</td><td>抛异常，中断</td><td>数据库操作，需保证一致性</td></tr>
     *   <tr><td>简历删除</td><td>抛异常，中断</td><td>核心操作，需保证一致性</td></tr>
     * </table>
     *
     * @param id 简历 ID
     * @throws BusinessException 简历不存在时抛出（RESUME_NOT_FOUND）
     */
    public void deleteResume(Long id) {
        log.info("收到删除简历请求: id={}", id);

        // 获取简历信息（用于删除存储文件）
        ResumeEntity resume = persistenceService.findById(id)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESUME_NOT_FOUND));

        // 1. 删除存储的文件（FileStorageService 已内置存在性检查）
        try {
            storageService.deleteResume(resume.getStorageKey());
        } catch (Exception e) {
            log.warn("删除存储文件失败，继续删除数据库记录: {}", e.getMessage());
        }

        // 2. 删除面试会话（会自动删除面试答案）
        interviewPersistenceService.deleteSessionsByResumeId(id);

        // 3. 删除数据库记录（包括分析记录）
        persistenceService.deleteResume(id);

        log.info("简历删除完成: id={}", id);
    }
}


