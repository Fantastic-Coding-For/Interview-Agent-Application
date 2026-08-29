package com.ywy.interviewagentapplication.modules.resume;

import com.ywy.interviewagentapplication.common.annotation.RateLimit;
import com.ywy.interviewagentapplication.common.result.Result;
import com.ywy.interviewagentapplication.modules.resume.model.ResumeDetailDTO;
import com.ywy.interviewagentapplication.modules.resume.model.ResumeListItemDTO;
import com.ywy.interviewagentapplication.modules.resume.service.ResumeDeleteService;
import com.ywy.interviewagentapplication.modules.resume.service.ResumeHistoryService;
import com.ywy.interviewagentapplication.modules.resume.service.ResumeUploadService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * <h1>简历管理 REST 控制器</h1>
 *
 * <p>简历模块的 HTTP API 入口，负责请求路由、参数校验、限流控制和响应格式化。</p>
 *
 * <h2>API 端点一览</h2>
 * <table border="1">
 *   <tr><th>方法</th><th>路径</th><th>功能</th><th>限流</th></tr>
 *   <tr><td>POST</td><td>/api/resumes/upload</td><td>上传并分析简历</td><td>全局 5次/s + IP 5次/s</td></tr>
 *   <tr><td>GET</td><td>/api/resumes</td><td>获取所有简历列表</td><td>无</td></tr>
 *   <tr><td>GET</td><td>/api/resumes/{id}/detail</td><td>获取简历详情</td><td>无</td></tr>
 *   <tr><td>GET</td><td>/api/resumes/{id}/export</td><td>导出分析报告 PDF</td><td>无</td></tr>
 *   <tr><td>DELETE</td><td>/api/resumes/{id}</td><td>删除简历</td><td>无</td></tr>
 *   <tr><td>POST</td><td>/api/resumes/{id}/reanalyze</td><td>重新分析简历</td><td>全局 2次/s + IP 2次/s</td></tr>
 *   <tr><td>GET</td><td>/api/resumes/health</td><td>健康检查</td><td>无</td></tr>
 * </table>
 *
 * <h2>限流策略说明</h2>
 * <p>通过自定义注解 {@link RateLimit} 实现双层限流：</p>
 * <ul>
 *   <li><b>全局维度</b>（GLOBAL）：限制所有用户的总请求量，保护后端服务不被压垮</li>
 *   <li><b>IP 维度</b>（IP）：限制单个 IP 的请求量，防止单个用户滥用</li>
 *   <li>上传接口更宽松（5次/s），重新分析更严格（2次/s），因为重新分析消耗 AI 调用成本</li>
 * </ul>
 *
 * <h2>ResponseEntity vs Result</h2>
 * <ul>
 *   <li>JSON 响应 → 使用 {@link Result} 包装，统一响应格式</li>
 *   <li>PDF 文件下载 → 使用 {@link ResponseEntity} 直接返回字节流，设置 Content-Disposition 头</li>
 * </ul>
 *
 * @see ResumeUploadService 上传和重新分析的业务逻辑
 * @see ResumeHistoryService 列表、详情、导出的业务逻辑
 * @see ResumeDeleteService 删除的业务逻辑
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "简历管理", description = "简历上传、分析、导出与删除")
public class ResumeController {

    private final ResumeUploadService uploadService;
    private final ResumeDeleteService deleteService;
    private final ResumeHistoryService historyService;

    /**
     * 上传简历并获取分析结果
     *
     * @param file 简历文件（支持PDF、DOCX、DOC、TXT、MD等）
     * @return 简历分析结果，包含评分和建议
     */
    @PostMapping(value = "/api/resumes/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<Map<String, Object>> uploadAndAnalyze(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = uploadService.uploadAndAnalyze(file);
        boolean isDuplicate = (Boolean) result.get("duplicate");
        if (isDuplicate) {
            return Result.success("检测到相同简历，已返回历史分析结果", result);
        }
        return Result.success(result);
    }

    /**
     * 获取所有简历列表
     */
    @GetMapping("/api/resumes")
    public Result<List<ResumeListItemDTO>> getAllResumes() {
        List<ResumeListItemDTO> resumes = historyService.getAllResumes();
        return Result.success(resumes);
    }

    /**
     * 获取简历详情（包含分析历史）
     */
    @GetMapping("/api/resumes/{id}/detail")
    public Result<ResumeDetailDTO> getResumeDetail(@PathVariable Long id) {
        ResumeDetailDTO detail = historyService.getResumeDetail(id);
        return Result.success(detail);
    }

    /**
     * 导出简历分析报告为PDF
     */
    @GetMapping("/api/resumes/{id}/export")
    public ResponseEntity<byte[]> exportAnalysisPdf(@PathVariable Long id) {
        try {
            var result = historyService.exportAnalysisPdf(id);
            String filename = URLEncoder.encode(result.filename(), StandardCharsets.UTF_8);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(result.pdfBytes());
        } catch (Exception e) {
            log.error("导出PDF失败: resumeId={}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 删除简历
     *
     * @param id 简历ID
     * @return 删除结果
     */
    @DeleteMapping("/api/resumes/{id}")
    public Result<Void> deleteResume(@PathVariable Long id) {
        deleteService.deleteResume(id);
        return Result.success(null);
    }

    /**
     * 重新分析简历（手动重试）
     * 用于分析失败后的重试
     *
     * @param id 简历ID
     * @return 结果
     */
    @PostMapping("/api/resumes/{id}/reanalyze")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 2)
    public Result<Void> reanalyze(@PathVariable Long id) {
        uploadService.reanalyze(id);
        return Result.success(null);
    }

    /**
     * 健康检查接口
     */
    @GetMapping("/api/resumes/health")
    public Result<Map<String, String>> health() {
        return Result.success(Map.of(
                "status", "UP",
                "service", "AI Interview Platform - Resume Service"
        ));
    }

}

