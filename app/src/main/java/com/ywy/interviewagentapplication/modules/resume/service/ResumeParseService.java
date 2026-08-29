package com.ywy.interviewagentapplication.modules.resume.service;

import com.ywy.interviewagentapplication.infrastructure.file.ContentTypeDetectionService;
import com.ywy.interviewagentapplication.infrastructure.file.DocumentParseService;
import com.ywy.interviewagentapplication.infrastructure.file.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * <h1>简历解析服务</h1>
 *
 * <p>本服务是简历文件解析的<b>门面（Facade）</b>，将所有解析工作委托给基础设施层的通用服务。
 * 本身不包含解析逻辑，只提供与"简历"业务语义对齐的方法签名。</p>
 *
 * <h2>委托关系</h2>
 * <pre>
 *  ResumeParseService（本类）         ← 业务门面，提供有业务语义的方法名
 *    ├── DocumentParseService         ← 通用文档解析（PDF/DOCX/DOC/TXT/MD → 纯文本）
 *    ├── ContentTypeDetectionService  ← 文件 MIME 类型检测
 *    └── FileStorageService           ← RustFS 文件存储（用于 downloadAndParse 场景）
 * </pre>
 *
 * <h2>设计意图：为什么加一层门面？</h2>
 * <ul>
 *   <li><b>语义隔离</b>：调用方只需知道"解析简历"，而不需了解底层用哪个文档解析库</li>
 *   <li><b>扩展点</b>：未来如需对简历文本做特殊处理（如脱敏、格式化），在此层添加</li>
 *   <li><b>测试友好</b>：上层服务只需 mock ResumeParseService，而非底层 DocumentParseService</li>
 * </ul>
 *
 * <h2>三种解析入口</h2>
 * <ol>
 *   <li>{@link #parseResume(MultipartFile)}：上传时直接解析 MultipartFile</li>
 *   <li>{@link #parseResume(byte[], String)}：从字节数组解析（用于非标准上传渠道）</li>
 *   <li>{@link #downloadAndParseContent(String, String)}：从 RustFS 下载后解析（用于重新分析）</li>
 * </ol>
 *
 * @see DocumentParseService 底层文档解析服务
 * @see ContentTypeDetectionService 文件类型检测服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeParseService {

    private final DocumentParseService documentParseService;
    private final ContentTypeDetectionService contentTypeDetectionService;
    private final FileStorageService storageService;

    /**
     * <h3>解析上传的简历文件</h3>
     *
     * <p>从 Spring MVC 返回的 MultipartFile 中提取纯文本内容。支持 PDF、DOCX、DOC、TXT、MD 等格式。</p>
     *
     * <p><b>注意：</b>扫描版 PDF（纯图片）无法提取文字，此时底层会返回空字符串。</p>
     *
     * @param file 上传的文件
     * @return 提取的纯文本内容
     */
    public String parseResume(MultipartFile file) {
        log.info("开始解析简历文件: {}", file.getOriginalFilename());
        return documentParseService.parseContent(file);
    }

    /**
     * <h3>从字节数组解析简历文件</h3>
     *
     * <p>适用于非标准上传渠道（如从消息队列接收、从其他存储系统读取）的场景。</p>
     *
     * @param fileBytes 文件字节数组
     * @param fileName  原始文件名（仅用于日志和格式推断）
     * @return 提取的纯文本内容
     */
    public String parseResume(byte[] fileBytes, String fileName) {
        log.info("开始解析简历文件（从字节数组）: {}", fileName);
        return documentParseService.parseContent(fileBytes, fileName);
    }

    /**
     * <h3>从 RustFS 下载文件并解析</h3>
     *
     * <p>用于<b>重新分析</b>场景：简历文件已存储在对象存储中，需要重新下载并提取文本。</p>
     *
     * @param storageKey       RustFS 中的文件 Key
     * @param originalFilename 原始文件名（用于格式推断和日志）
     * @return 提取的纯文本内容
     */
    public String downloadAndParseContent(String storageKey, String originalFilename) {
        log.info("从存储下载并解析简历文件: {}", originalFilename);
        return documentParseService.downloadAndParseContent(storageService, storageKey, originalFilename);
    }

    /**
     * <h3>检测文件的真实 MIME 类型</h3>
     *
     * <p>基于文件<b>内容</b>而非扩展名检测，
     * 防止用户通过修改扩展名绕过类型限制。</p>
     *
     * @param file 上传的文件
     * @return MIME 类型字符串（如 "application/pdf"）
     */
    public String detectContentType(MultipartFile file) {
        return contentTypeDetectionService.detectContentType(file);
    }
}

