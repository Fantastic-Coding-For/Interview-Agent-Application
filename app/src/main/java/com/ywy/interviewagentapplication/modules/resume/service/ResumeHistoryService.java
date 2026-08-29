package com.ywy.interviewagentapplication.modules.resume.service;

import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.infrastructure.export.PdfExportService;
import com.ywy.interviewagentapplication.infrastructure.mapper.InterviewMapper;
import com.ywy.interviewagentapplication.infrastructure.mapper.ResumeMapper;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewHistoryItemDTO;
import com.ywy.interviewagentapplication.modules.interview.model.ResumeAnalysisResponse;
import com.ywy.interviewagentapplication.modules.interview.service.InterviewPersistenceService;
import com.ywy.interviewagentapplication.modules.resume.model.ResumeAnalysisEntity;
import com.ywy.interviewagentapplication.modules.resume.model.ResumeDetailDTO;
import com.ywy.interviewagentapplication.modules.resume.model.ResumeEntity;
import com.ywy.interviewagentapplication.modules.resume.model.ResumeListItemDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * <h1>简历历史查询服务</h1>
 *
 * <p>负责简历数据的<b>查询和展示</b>，是简历模块的读操作聚合层。</p>
 *
 * <h2>与 PersistenceService 的分工</h2>
 * <table border="1">
 *   <tr><th>ResumePersistenceService</th><th>ResumeHistoryService（本类）</th></tr>
 *   <tr><td>数据库 CRUD 操作</td><td>查询 + 组装展示数据</td></tr>
 *   <tr><td>返回 Entity / 业务 DTO</td><td>返回聚合后的列表/详情 DTO</td></tr>
 *   <tr><td>不含业务逻辑</td><td>跨服务聚合（简历 + 分析 + 面试）</td></tr>
 *   <tr><td>含写事务</td><td>纯读操作（除 PDF 导出）</td></tr>
 * </table>
 *
 * <h2>核心功能</h2>
 * <ul>
 *   <li><b>列表查询</b>：汇总所有简历的概要信息（最新分数、面试次数等）</li>
 *   <li><b>详情查询</b>：单份简历的完整信息（分析历史 + 面试历史）</li>
 *   <li><b>PDF 导出</b>：将分析结果导出为 PDF 格式的报告</li>
 * </ul>
 *
 * <h2>数据聚合模式</h2>
 * <p>列表查询中每条记录需要聚合 3 个数据源：</p>
 * <pre>
 *  ResumeListItemDTO
 *    ├── ResumeEntity           → 基本信息（文件名、大小、上传时间）
 *    ├── ResumeAnalysisEntity   → 最新分数、分析时间
 *    └── InterviewSessionEntity → 面试次数
 * </pre>
 * <p>MapStruct 负责基础字段映射，JSON 字段（strengths/suggestions）通过回调方法反序列化。</p>
 *
 * @see ResumePersistenceService 底层数据存取服务
 * @see PdfExportService PDF 导出服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeHistoryService {

    private final ResumePersistenceService resumePersistenceService;
    private final InterviewPersistenceService interviewPersistenceService;
    private final PdfExportService pdfExportService;
    private final ObjectMapper objectMapper;
    private final ResumeMapper resumeMapper;
    private final InterviewMapper interviewMapper;

    /**
     * <h3>获取所有简历列表（聚合视图）</h3>
     *
     * <p>对每份简历聚合以下信息：</p>
     * <ul>
     *   <li>基本信息（文件名、大小、上传时间、访问次数）</li>
     *   <li>最新分析分数（无分析记录时为 null）</li>
     *   <li>最近分析时间</li>
     *   <li>面试次数</li>
     *   <li>当前分析状态（PENDING / PROCESSING / COMPLETED / FAILED）</li>
     * </ul>
     *
     * <p><b>性能考虑：</b>当前实现每份简历执行 2 次额外查询（分析 + 面试），
     * 即 N+1 查询问题。在简历数量较大时，可优化为批量查询。</p>
     *
     * @return 简历列表 DTO（含聚合数据）
     */
    public List<ResumeListItemDTO> getAllResumes() {
        List<ResumeEntity> resumes = resumePersistenceService.findAllResumes();

        return resumes.stream().map(resume -> {
            // 获取最新分析结果的分数
            Integer latestScore = null;
            LocalDateTime lastAnalyzedAt = null;
            Optional<ResumeAnalysisEntity> analysisOpt = resumePersistenceService.getLatestAnalysis(resume.getId());
            if (analysisOpt.isPresent()) {
                ResumeAnalysisEntity analysis = analysisOpt.get();
                latestScore = analysis.getOverallScore();
                lastAnalyzedAt = analysis.getAnalyzedAt();
            }

            // 获取面试次数
            int interviewCount = interviewPersistenceService.findByResumeId(resume.getId()).size();

            // 使用 MapStruct 映射
            return new ResumeListItemDTO(
                    resume.getId(),
                    resume.getOriginalFilename(),
                    resume.getFileSize(),
                    resume.getUploadedAt(),
                    resume.getAccessCount(),
                    latestScore,
                    lastAnalyzedAt,
                    interviewCount,
                    resume.getAnalyzeStatus(),
                    resume.getAnalyzeError()
            );
        }).toList();
    }

    /**
     * <h3>获取简历详情（含完整分析历史和面试历史）</h3>
     *
     * <p>返回单份简历的完整信息，包含：</p>
     * <ul>
     *   <li>简历基本信息（文件名、大小、文本内容、存储 URL 等）</li>
     *   <li><b>所有分析历史</b>（按时间降序，包含评分明细和改进建议）</li>
     *   <li><b>所有面试历史</b></li>
     *   <li>当前分析状态和错误信息</li>
     * </ul>
     *
     * <p><b>JSON 字段回调转换：</b></p>
     * <p>分析实体中的 strengths 和 suggestions 以 JSON 字符串存储，
     * 通过方法引用 {@code this::extractStrengths} 和 {@code this::extractSuggestions}
     * 传递给 MapStruct 作为自定义转换逻辑。</p>
     *
     * @param id 简历 ID
     * @return 简历详情 DTO
     * @throws BusinessException 简历不存在时抛出（RESUME_NOT_FOUND）
     */
    public ResumeDetailDTO getResumeDetail(Long id) {
        Optional<ResumeEntity> resumeOpt = resumePersistenceService.findById(id);
        if (resumeOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        }

        ResumeEntity resume = resumeOpt.get();

        // 获取所有分析记录，使用 MapStruct 批量转换
        List<ResumeAnalysisEntity> analyses = resumePersistenceService.findAnalysesByResumeId(id);
        List<ResumeDetailDTO.AnalysisHistoryDTO> analysisHistory = resumeMapper.toAnalysisHistoryDTOList(
                analyses,
                this::extractStrengths,
                this::extractSuggestions
        );

        // 使用 InterviewMapper 转换面试历史
        List<InterviewHistoryItemDTO> interviewHistory = interviewMapper.toInterviewHistoryList(
                interviewPersistenceService.findByResumeId(id)
        );

        return new ResumeDetailDTO(
                resume.getId(),
                resume.getOriginalFilename(),
                resume.getFileSize(),
                resume.getContentType(),
                resume.getStorageUrl(),
                resume.getUploadedAt(),
                resume.getAccessCount(),
                resume.getResumeText(),
                resume.getAnalyzeStatus(),
                resume.getAnalyzeError(),
                analysisHistory,
                interviewHistory
        );
    }

    /**
     * <h3>从分析实体的 JSON 字段中提取 strengths 列表</h3>
     *
     * <p>作为方法引用（{@code this::extractStrengths}）传递给 MapStruct，
     * 用于在批量转换分析历史时反序列化 JSON 字段。</p>
     *
     * <p><b>容错：</b>JSON 解析失败时返回空列表，不中断整个转换流程。</p>
     *
     * @param entity 分析实体
     * @return strengths 列表（解析失败时返回空列表）
     */
    private List<String> extractStrengths(ResumeAnalysisEntity entity) {
        try {
            if (entity.getStrengthsJson() != null) {
                return objectMapper.readValue(
                        entity.getStrengthsJson(),
                        new TypeReference<>() {
                        }
                );
            }
        } catch (JacksonException e) {
            log.error("解析 strengths JSON 失败", e);
        }
        return List.of();
    }

    /**
     * <h3>从分析实体的 JSON 字段中提取 suggestions 列表</h3>
     *
     * <p>与 {@link #extractStrengths} 对称，用于反序列化 suggestions 字段。</p>
     *
     * <p>返回类型为 {@code List<Object>} 而非 {@code List<Suggestion>}，
     * 因为 MapStruct 的转换方法签名中使用了泛型擦除。</p>
     *
     * @param entity 分析实体
     * @return suggestions 列表（解析失败时返回空列表）
     */
    private List<Object> extractSuggestions(ResumeAnalysisEntity entity) {
        try {
            if (entity.getSuggestionsJson() != null) {
                return objectMapper.readValue(
                        entity.getSuggestionsJson(),
                        new TypeReference<>() {
                        }
                );
            }
        } catch (JacksonException e) {
            log.error("解析 suggestions JSON 失败", e);
        }
        return List.of();
    }

    /**
     * <h3>导出简历分析报告为 PDF</h3>
     *
     * <p>流程：</p>
     * <ol>
     *   <li>查询简历实体（不存在则抛异常）</li>
     *   <li>查询最新分析结果（不存在则抛异常，因为无分析结果无法导出报告）</li>
     *   <li>调用 PDF 导出服务生成 PDF 字节数组</li>
     * </ol>
     *
     * <p>返回的 {@link ExportResult} 包含 PDF 字节数组和建议的文件名，
     * Controller 层负责设置 HTTP 响应头（Content-Disposition 等）。</p>
     *
     * @param resumeId 简历 ID
     * @return PDF 导出结果（字节数组 + 文件名）
     * @throws BusinessException 简历不存在、无分析结果或导出失败时抛出
     */
    public ExportResult exportAnalysisPdf(Long resumeId) {
        Optional<ResumeEntity> resumeOpt = resumePersistenceService.findById(resumeId);
        if (resumeOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        }

        ResumeEntity resume = resumeOpt.get();
        Optional<ResumeAnalysisResponse> analysisOpt = resumePersistenceService.getLatestAnalysisAsDTO(resumeId);
        if (analysisOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_ANALYSIS_NOT_FOUND);
        }

        try {
            byte[] pdfBytes = pdfExportService.exportResumeAnalysis(resume, analysisOpt.get());
            String filename = "简历分析报告_" + resume.getOriginalFilename() + ".pdf";

            return new ExportResult(pdfBytes, filename);
        } catch (Exception e) {
            log.error("导出PDF失败: resumeId={}", resumeId, e);
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "导出PDF失败: " + e.getMessage());
        }
    }

    /**
     * 用于封装 PDF 导出的字节数组和建议文件名的 Record，Controller 层可直接使用。
     */
    public record ExportResult(byte[] pdfBytes, String filename) {}
}


