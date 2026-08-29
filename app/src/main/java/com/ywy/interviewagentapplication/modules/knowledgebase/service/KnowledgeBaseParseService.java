package com.ywy.interviewagentapplication.modules.knowledgebase.service;

import com.ywy.interviewagentapplication.infrastructure.file.ContentTypeDetectionService;
import com.ywy.interviewagentapplication.infrastructure.file.DocumentParseService;
import com.ywy.interviewagentapplication.infrastructure.file.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库解析服务：文档文本提取的<b>门面</b>。
 *
 * <h3>架构角色</h3>
 * 本 Service 不实现解析逻辑，只做日志记录和解析委托。全部解析能力
 * 由基础设施层的 {@link DocumentParseService} 提供（基于 Apache Tika）。
 * 为什么需要这一层门面？
 * <ul>
 *   <li><b>模块边界</b>：知识库模块不应直接依赖基础设施层的具体实现细节，
 *       门面将依赖收敛到一个点（未来替换解析引擎只需改本类）</li>
 *   <li><b>统一日志</b>：所有知识库文件的解析入口都有统一的日志记录，
 *       便于追踪"哪个知识库解析了哪个文件"</li>
 *   <li><b>参数适配</b>：将知识库场景的参数（MultipartFile/byte[]/storageKey）
 *       适配为通用解析服务的接口</li>
 * </ul>
 *
 * <h3>支持的文件类型</h3>
 * 依赖 Tika 的能力：PDF、DOCX、DOC、TXT、MD 等常见文档格式。
 * 类型白名单由 FileValidationService 在上传时校验。
 *
 * @see DocumentParseService 底层解析实现
 * @see ContentTypeDetectionService MIME 类型检测
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseParseService {

    private final DocumentParseService documentParseService;
    private final ContentTypeDetectionService contentTypeDetectionService;
    private final FileStorageService storageService;

    /**
     * 解析上传的知识库文件，提取纯文本内容（经过清洗）。
     * <p>
     * 解析出的文本将用于后续的向量化（分块 + Embedding），所以文本质量直接影响检索效果，因此解析是知识库链路的关键环节。
     *
     * @param file 上传的文件（支持 PDF、DOCX、DOC、TXT、MD 等）
     * @return 提取的纯文本内容（经过清洗）
     */
    public String parseContent(MultipartFile file) {
        log.info("开始解析知识库文件: {}", file.getOriginalFilename());
        return documentParseService.parseContent(file);
    }

    /**
     * 解析字节数组形式的文件内容（经过清洗）
     *
     * @param fileBytes 文件字节数组
     * @param fileName  原始文件名（用于日志）
     * @return 提取的文本内容（经过清洗）
     */
    public String parseContent(byte[] fileBytes, String fileName) {
        log.info("开始解析知识库文件（从字节数组）: {}", fileName);
        return documentParseService.parseContent(fileBytes, fileName);
    }

    /**
     * 从 RustFS 存储下载文件并解析内容（经过清洗）。
     *
     * @param storageKey       存储键
     * @param originalFilename 原始文件名
     * @return 提取的文本内容（经过清洗）
     */
    public String downloadAndParseContent(String storageKey, String originalFilename) {
        log.info("从存储下载并解析知识库文件: {}", originalFilename);
        return documentParseService.downloadAndParseContent(storageService, storageKey, originalFilename);
    }

    /**
     * 检测文件的 MIME 类型（基于内容魔数而非扩展名）。
     * <p>
     * 使用 Tika 的魔术字节检测，这比 HTTP 的 Content-Type 头更可靠
     * （Content-Type 由客户端声明，可能被伪造或不准确）。
     *
     * @param file 上传的文件
     * @return 检测到的 MIME 类型（如 "application/pdf"）
     */
    public String detectContentType(MultipartFile file) {
        return contentTypeDetectionService.detectContentType(file);
    }
}

