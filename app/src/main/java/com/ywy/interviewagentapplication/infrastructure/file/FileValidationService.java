package com.ywy.interviewagentapplication.infrastructure.file;

import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.function.Predicate;

/**
 * 文件合法性校验服务：验证文件的 MIME 类型是否被程序接受。
 *
 * <h2>设计思路</h2>
 * 将文件验证逻辑从业务 Service 中抽离为独立服务，原因：
 * <ul>
 *   <li><b>单一职责：</b>验证逻辑和业务逻辑应该分开，各自独立变更</li>
 *   <li><b>复用性：</b>简历上传、知识库上传都需要相同的文件大小/类型校验</li>
 *   <li><b>可测试性：</b>验证逻辑可独立进行单元测试，不依赖 S3、数据库等外部资源</li>
 * </ul>
 *
 * <h2>验证维度</h2>
 * <ol>
 *   <li><b>基本属性：</b>文件非空 + 大小不超限</li>
 *   <li><b>MIME 类型（列表匹配）：</b>基于 Content-Type 做子串匹配</li>
 *   <li><b>MIME 类型 + 扩展名（组合匹配）：</b>双重校验，任一通过即放行</li>
 * </ol>
 *
 * <h2>为什么 MIME + 扩展名双重检查</h2>
 * HTTP 的 Content-Type 由客户端设置，可能被篡改或不准确；
 * 文件扩展名也可能被随意修改（如将 .exe 改为 .pdf）。
 * 两者结合检查：只要有一个维度可信就放行。这是一种"宽松但合理"的策略：
 * 能被篡改的文件类型检测本就不是安全防线，真正的恶意文件需要杀毒引擎；
 * 这里的验证主要是<b>用户体验层面</b>的（"请上传 PDF 文件"），而非安全层面。
 * <h2>和 ContentTypeDetectionService 的区别</h2>
 * <p>{@link ContentTypeDetectionService} 用于精确检测文件本身的 MIME 类型</p>
 * <p>{@link FileValidationService} 用于精确检测文件的 MIME 类型是否被应用支持</p>
 */
@Slf4j
@Service
public class FileValidationService {

    /**
     * 验证文件基本属性：非空检查 + 大小限制。
     *
     * <h3>校验顺序</h3>
     * 先检查空文件再检查大小。若文件为空，大小检查无意义且可能产生误导性的错误消息。
     *
     * @param file        上传的文件
     * @param maxSizeBytes 最大允许的文件大小（字节）
     * @param fileTypeName 文件类型名称（用于构造友好的错误消息，如"简历"、"知识库"）
     * @throws BusinessException 若文件为空或超过大小限制（错误码：BAD_REQUEST）
     */
    public void validateFile(MultipartFile file, long maxSizeBytes, String fileTypeName) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    String.format("请选择要上传的%s文件", fileTypeName));
        }

        if (file.getSize() > maxSizeBytes) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件大小超过限制");
        }
    }

    /**
     * 验证文件类型是否被应用支持（基于 MIME 类型列表的双向子串包含匹配）。
     *
     * <h3>匹配策略：子串包含</h3>
     * 例如允许类型列表包含 {@code "pdf"} 时：
     * <ul>
     *   <li>{@code "application/pdf"} → 匹配（包含 "pdf"）</li>
     *   <li>{@code "application/pdf;charset=utf-8"} → 匹配</li>
     *   <li>{@code "application/octet-stream"} → 不匹配</li>
     * </ul>
     * 双向包含（contentType 包含 allowed 或 allowed 包含 contentType）
     * 提供最大兼容性，适应不同浏览器/客户端上传时的 MIME 差异。
     *
     * @param contentType  文件的 MIME 类型（来自 HTTP Content-Type 头部）
     * @param allowedTypes 允许的 MIME 类型关键字列表（如 ["pdf", "msword"]）
     * @param errorMessage 验证失败时的自定义错误消息（为 null 时使用默认消息）
     * @throws BusinessException 若类型不匹配（错误码：BAD_REQUEST）
     */
    public void validateContentTypeByList(String contentType, List<String> allowedTypes, String errorMessage) {
        if (!isAllowedType(contentType, allowedTypes)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    errorMessage != null ? errorMessage : "不支持的文件类型: " + contentType);
        }
    }

    /**
     * 验证文件类型是否被应用支持（MIME 类型 + 文件扩展名双重检查，调用方定义检查逻辑）。
     *
     * <h3>检查策略：任一通过即放行</h3>
     * <pre>
     * if (mimeTypeChecker.test(contentType)) → 放行
     * if (extensionChecker.test(fileName))  → 放行
     * else → 拒绝
     * </pre>
     * 先检查 MIME（更快，无需解析文件名），失败后再检查扩展名。
     * 这种短路求值在大多数情况下 MIME 就足够，无需走扩展名逻辑。
     *
     * <h3>为什么用 Predicate 而非硬编码规则</h3>
     * {@link java.util.function.Predicate} 让调用方自由定义检查逻辑：
     * <ul>
     *   <li>简历上传：检查 PDF/DOCX 的 MIME 和扩展名</li>
     *   <li>知识库上传：检查 MD/PDF/DOCX/TXT 等更宽泛的类型</li>
     * </ul>
     * 服务本身不关心"什么文件类型是合法的"，只负责执行"调用方定义的检查规则"。
     * 这是 <b>Strategy 模式</b> 的简化应用。
     *
     * @param contentType       文件的 MIME 类型
     * @param fileName          文件名（用于扩展名检查，可为 null）
     * @param mimeTypeChecker   MIME 类型检查器（Predicate，由调用方定义规则）
     * @param extensionChecker  文件扩展名检查器（Predicate，由调用方定义规则）
     * @param errorMessage      验证失败时的自定义错误消息
     * @throws BusinessException 若 MIME 和扩展名都不匹配（错误码：BAD_REQUEST）
     */
    public void validateContentType(String contentType, String fileName,
                                    Predicate<String> mimeTypeChecker,
                                    Predicate<String> extensionChecker,
                                    String errorMessage) {
        // 先检查MIME类型
        if (mimeTypeChecker.test(contentType)) {
            return;
        }

        // 如果MIME类型不支持，再检查文件扩展名
        if (fileName != null && extensionChecker.test(fileName)) {
            return;
        }

        throw new BusinessException(ErrorCode.BAD_REQUEST,
                errorMessage != null ? errorMessage : "不支持的文件类型: " + contentType);
    }

    /**
     * 检查 MIME 类型是否在允许列表中（双向子串包含匹配）。
     *
     * <h3>为什么用子串包含而非精确匹配</h3>
     * 同一文件类型在不同操作系统/浏览器/客户端下可能有多种 MIME 变体：
     * <ul>
     *   <li>PDF：application/pdf, application/x-pdf, application/acrobat</li>
     *   <li>DOCX：application/vnd.openxmlformats-officedocument.wordprocessingml.document</li>
     *   <li>Markdown：text/markdown, text/x-markdown, text/x-web-markdown</li>
     * </ul>
     * 精确匹配需要穷举所有变体且难以维护。双向子串包含匹配：只要允许类型字符串包含待检查类型字符串，或者反包含，则视为匹配。
     *
     * @param contentType  待检查的 MIME 类型（可能为 null）
     * @param allowedTypes 允许的类型列表
     * @return {@code true} 类型被允许；{@code false} 不允许或无法判断
     */
    private boolean isAllowedType(String contentType, List<String> allowedTypes) {
        if (contentType == null || allowedTypes == null || allowedTypes.isEmpty()) {
            return false;
        }

        String lowerContentType = contentType.toLowerCase();
        return allowedTypes.stream()
                .anyMatch(allowed -> {
                    String lowerAllowed = allowed.toLowerCase();
                    return lowerContentType.contains(lowerAllowed) || lowerAllowed.contains(lowerContentType);
                });
    }

    /**
     * 判断文件名是否为 Markdown 格式（基于扩展名）。
     *
     * <p>支持三种常见 Markdown 扩展名：.md / .markdown / .mdown。
     *
     * @param fileName 文件名（可为 null）
     * @return {@code true} 是 Markdown 文件
     */
    public boolean isMarkdownExtension(String fileName) {
        if (fileName == null) {
            return false;
        }

        String lowerFileName = fileName.toLowerCase();
        return lowerFileName.endsWith(".md") ||
                lowerFileName.endsWith(".markdown") ||
                lowerFileName.endsWith(".mdown");
    }

    /**
     * 判断 MIME 类型是否为知识库支持的文档格式。
     *
     * <h3>支持的类型</h3>
     * <ul>
     *   <li>PDF：application/pdf 及其变体</li>
     *   <li>DOC：application/msword</li>
     *   <li>DOCX：application/vnd.openxmlformats-officedocument.wordprocessingml.*</li>
     *   <li>纯文本：text/plain</li>
     *   <li>Markdown：text/markdown, text/x-markdown, text/x-web-markdown</li>
     *   <li>RTF：application/rtf</li>
     * </ul>
     *
     * @param contentType MIME 类型字符串（可能为 null）
     * @return {@code true} 是知识库支持的格式
     */
    public boolean isKnowledgeBaseMimeType(String contentType) {
        if (contentType == null) {
            return false;
        }

        String lowerContentType = contentType.toLowerCase();
        return lowerContentType.contains("pdf") ||
                lowerContentType.contains("msword") ||
                lowerContentType.contains("wordprocessingml") ||
                lowerContentType.contains("text/plain") ||
                lowerContentType.contains("text/markdown") ||
                lowerContentType.contains("text/x-markdown") ||
                lowerContentType.contains("text/x-web-markdown") ||
                lowerContentType.contains("application/rtf");
    }
}


