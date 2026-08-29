package com.ywy.interviewagentapplication.modules.knowledgebase;

import com.ywy.interviewagentapplication.common.annotation.RateLimit;
import com.ywy.interviewagentapplication.common.result.Result;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseStatsDTO;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.QueryRequest;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.QueryResponse;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.VectorStatus;
import com.ywy.interviewagentapplication.modules.knowledgebase.service.KnowledgeBaseDeleteService;
import com.ywy.interviewagentapplication.modules.knowledgebase.service.KnowledgeBaseListService;
import com.ywy.interviewagentapplication.modules.knowledgebase.service.KnowledgeBaseQueryService;
import com.ywy.interviewagentapplication.modules.knowledgebase.service.KnowledgeBaseUploadService;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 知识库操作控制器。
 *
 * <h3>API 分组</h3>
 * <ul>
 *   <li><b>基础 CRUD</b>：列表、详情、删除</li>
 *   <li><b>RAG 问答</b>：query（同步）、query/stream（SSE 流式）</li>
 *   <li><b>分类管理</b>：分类列表、按分类筛选、更新分类</li>
 *   <li><b>上传下载</b>：multipart 上传、文件下载</li>
 *   <li><b>搜索统计</b>：关键词搜索、聚合统计</li>
 *   <li><b>向量化管理</b>：手动重试向量化</li>
 * </ul>
 *
 * <h3>限流策略总览</h3>
 * 限流的重点是<b>昂贵操作</b>（LLM 调用、文件处理）：
 * <ul>
 *   <li>query：10次/秒（全局+IP）——LLM 调用</li>
 *   <li>query/stream：5次/秒（全局+IP）——流式 LLM 调用（连接更昂贵）</li>
 *   <li>upload：3次/秒（全局+IP）——文件解析 + 存储</li>
 *   <li>revectorize：2次/秒（全局+IP）——向量化是重操作</li>
 * </ul>
 * 查询类接口（列表、详情、搜索、统计）不限流——纯数据库读取，成本低。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "知识库管理", description = "知识库上传、下载、查询、分类与向量化")
public class KnowledgeBaseController {

    private final KnowledgeBaseUploadService uploadService;
    private final KnowledgeBaseQueryService queryService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseDeleteService deleteService;

    /**
     * 获取所有知识库详情 DTO 列表（支持状态过滤，和按字段排序）
     */
    @GetMapping("/api/knowledgebase/list")
    public Result<List<KnowledgeBaseListItemDTO>> getAllKnowledgeBases(
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "vectorStatus", required = false) String vectorStatus) {

        VectorStatus status = null;
        if (vectorStatus != null && !vectorStatus.isBlank()) {
            try {
                status = VectorStatus.valueOf(vectorStatus.toUpperCase());
            } catch (IllegalArgumentException e) {
                return Result.error("无效的向量化状态: " + vectorStatus);
            }
        }

        return Result.success(listService.listKnowledgeBases(status, sortBy));
    }

    /**
     * 获取知识库详情。
     * 不存在时返回错误 Result（而非抛异常），前端统一处理 error 字段。
     */
    @GetMapping("/api/knowledgebase/{id}")
    public Result<KnowledgeBaseListItemDTO> getKnowledgeBase(@PathVariable Long id) {
        return listService.getKnowledgeBase(id)
                .map(Result::success)
                .orElse(Result.error("知识库不存在"));
    }

    /**
     * 删除知识库（级联：RAG 会话关联 → 向量数据 → RustFS 文件 → 数据库记录）。
     * <p>
     * 注意：删除操作按"数据库优先，文件尽力而为"的顺序执行。
     */
    @DeleteMapping("/api/knowledgebase/{id}")
    public Result<Void> deleteKnowledgeBase(@PathVariable Long id) {
        deleteService.deleteKnowledgeBase(id);
        return Result.success(null);
    }

    /**
     * 基于知识库回答问题（同步模式，支持引用多知识库）。
     * <p>
     * <b>限流</b>：全局 + IP 各 10次/秒——LLM 调用成本较高。
     * <p>
     * 同步模式适用于短回答场景；长回答建议使用
     * {@link #queryKnowledgeBaseStream}（SSE 流式输出）。
     */
    @PostMapping("/api/knowledgebase/query")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10)
    public Result<QueryResponse> queryKnowledgeBase(@Valid @RequestBody QueryRequest request) {
        return Result.success(queryService.queryKnowledgeBase(request));
    }

    /**
     * 基于知识库回答问题（流式 SSE 模式，支持引用多知识库）。
     * <p>
     * <b>为什么返回 Flux 而非 Result？</b>
     * SSE（Server-Sent Events）的本质是一个长连接上持续推送数据流——
     * 无法用一次性返回的 Result 表达。Flux&lt;String&gt; 由 WebFlux 框架
     * 自动转为 {@code text/event-stream} 响应。
     * <p>
     * <b>限流更严格的原因</b>（5次/秒 vs 10次/秒）：
     * 流式请求占用的连接时间更长（LLM 逐 token 输出），
     * 同时在线连接数直接消耗服务器线程/内存资源。
     *
     * @return SSE 数据流（每次推送一个文本片段）
     */
    @PostMapping(value = "/api/knowledgebase/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Flux<String> queryKnowledgeBaseStream(@Valid @RequestBody QueryRequest request) {
        log.debug("收到知识库流式查询请求: kbIds={}, question={}, 线程: {} (虚拟线程: {})",
                request.knowledgeBaseIds(), request.question(), Thread.currentThread(), Thread.currentThread().isVirtual());
        return queryService.answerQuestionStream(request.knowledgeBaseIds(), request.question());
    }

    // ========== 分类管理 API ==========

    /**
     * 获取所有分类（去重排序后的分类名列表）
     */
    @GetMapping("/api/knowledgebase/categories")
    public Result<List<String>> getAllCategories() {
        return Result.success(listService.getAllCategories());
    }

    /**
     * 根据分类获取知识库详情 DTO 列表
     */
    @GetMapping("/api/knowledgebase/category/{category}")
    public Result<List<KnowledgeBaseListItemDTO>> getByCategory(@PathVariable String category) {
        return Result.success(listService.listByCategory(category));
    }

    /**
     * 获取未分类的知识库详情 DTO 列表
     */
    @GetMapping("/api/knowledgebase/uncategorized")
    public Result<List<KnowledgeBaseListItemDTO>> getUncategorized() {
        return Result.success(listService.listByCategory(null));
    }

    /**
     * 更新知识库分类。
     */
    @PutMapping("/api/knowledgebase/{id}/category")
    public Result<Void> updateCategory(@PathVariable Long id, @RequestBody Map<String, String> body) {
        listService.updateCategory(id, body.get("category"));
        return Result.success(null);
    }

    // ========== 上传下载 API ==========

    /**
     * 上传知识库文件（multipart/form-data）。
     * <p>
     * <b>限流</b>：全局 + IP 各 3次/秒。文件解析（Tika）+ SHA-256 计算 + RustFS 存储 + 向量化 都是重操作。
     * <p>
     * <b>上传流程</b>（在 UploadService 中）：
     * 验证 → 类型检测 → 去重检查 → 文本解析 → 存储 → 元数据入库 → 向量化入队。
     * 返回结果包含 duplicate 字段，表示"已存在相同知识库"。
     *
     * @param file     上传的文件（支持 PDF、DOCX、DOC、TXT、MD 等）
     * @param name     知识库名称（可选，为空则从文件名提取）
     * @param category 分类（可选）
     * @return 上传结果（含 knowledgeBase 元数据、storage 信息、duplicate 标记）
     */
    @PostMapping(value = "/api/knowledgebase/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 3)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 3)
    public Result<Map<String, Object>> uploadKnowledgeBase(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "category", required = false) String category) {
        return Result.success(uploadService.uploadKnowledgeBase(file, name, category));
    }

    /**
     * 下载知识库原始文件。
     * <p>
     * <b>文件名编码的双保险</b>：
     * <ul>
     *   <li>{@code filename="..."}：传统格式（RFC 6266）——兼容老浏览器</li>
     *   <li>{@code filename*=UTF-8''...}：RFC 5987 格式——支持中文文件名的新浏览器</li>
     * </ul>
     * 同时提供两种格式，浏览器自动选择其支持的版本。
     * {@code replaceAll("\\+", "%20")}：URLEncoder 将空格编码为 '+'，
     * 但 Content-Disposition 中空格应使用 %20——修正这一差异。
     */
    @GetMapping("/api/knowledgebase/{id}/download")
    public ResponseEntity<byte[]> downloadKnowledgeBase(@PathVariable Long id) {
        var entity = listService.getEntityForDownload(id);
        byte[] fileContent = listService.downloadFile(id);

        String filename = entity.getOriginalFilename();
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename)
                .header(HttpHeaders.CONTENT_TYPE,
                        entity.getContentType() != null ? entity.getContentType()
                                : MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .body(fileContent);
    }

    // ========== 搜索 API ==========

    /**
     * 按关键词搜索知识库详情 DTO 列表（名称或文件名的模糊匹配）。
     */
    @GetMapping("/api/knowledgebase/search")
    public Result<List<KnowledgeBaseListItemDTO>> search(@RequestParam("keyword") String keyword) {
        return Result.success(listService.search(keyword));
    }

    // ========== 统计 API ==========

    /**
     * 获取所有知识库的统计信息。
     */
    @GetMapping("/api/knowledgebase/stats")
    public Result<KnowledgeBaseStatsDTO> getStatistics() {
        return Result.success(listService.getStatistics());
    }

    // ========== 向量化管理 API ==========

    /**
     * 重新向量化知识库（手动重试），用于向量化失败后的重试
     */
    @PostMapping("/api/knowledgebase/{id}/revectorize")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 2)
    public Result<Void> revectorize(@PathVariable Long id) {
        uploadService.revectorize(id);
        return Result.success(null);
    }

}


