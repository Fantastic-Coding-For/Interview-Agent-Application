package com.ywy.interviewagentapplication.infrastructure.file;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文件类型确认服务，使用 Apache Tika 进行精确的 MIME 类型检测。
 *
 * <h2>为什么需要专门的 MIME 检测服务</h2>
 * HTTP 上传时的 {@code Content-Type} 头部由客户端设置，存在两个问题：
 * <ol>
 *   <li><b>不可靠：</b>客户端可能发送错误的 Content-Type（如将所有文件标记为
 *       {@code application/octet-stream}）</li>
 *   <li><b>可伪造：</b>恶意用户可篡改 Content-Type 以绕过前端验证</li>
 * </ol>
 * Apache Tika 通过读取文件的<b>魔数（magic bytes）</b>进行基于内容的检测，
 * 比依赖 HTTP 头部更可靠。例如：
 * <ul>
 *   <li>PDF 文件的前 4 字节是 {@code 25 50 44 46}（"%PDF"）</li>
 *   <li>DOCX 文件的内部结构是 ZIP（前 2 字节 {@code 50 4B}）</li>
 * </ul>
 *
 * <h2>设计权衡：Tika.detect() vs TikaConfig</h2>
 * 当前使用 {@code new Tika()} 默认实例，而非通过 {@code TikaConfig} 自定义配置。
 * 原因：
 * <ul>
 *   <li>默认配置覆盖了 1500+ 种 MIME 类型，满足当前所有需求</li>
 *   <li>自定义 TikaConfig 需要维护 XML 配置文件，收益不成比例</li>
 *   <li>若需支持非标格式，可在后续迭代中引入自定义配置</li>
 * </ul>
 *
 * <h2>降级策略</h2>
 * 当 Tika 检测失败时（如输入流不可读）：
 * <ul>
 *   <li>对于 MultipartFile：回退到 HTTP 的 Content-Type 头</li>
 *   <li>对于 InputStream：返回 {@code "application/octet-stream"}（未知二进制）</li>
 *   <li>对于 byte[]：不会检测失败，因为数据已经存在于内存中</li>
 * </ul>
 * 这种降级保证了服务不会因 MIME 检测失败而中断业务流程。
 */
@Slf4j
@Service
public class ContentTypeDetectionService {
    /**
     * Apache Tika 实例（线程安全，单例复用）。
     * <p>
     * Tika 类内部维护了 MIME 类型检测规则库，
     * 每次调用 {@code detect()} 方法都复用同一套推理规则。
     * Tika 对象是线程安全的，可以在多线程环境中共享。
     */
    private final Tika tika;

    public ContentTypeDetectionService() {
        this.tika = new Tika();
    }

    /**
     * 检测 MultipartFile 文件的 MIME 类型。
     *
     * <h3>执行逻辑</h3>
     * <ol>
     *   <li>从 MultipartFile 获取 InputStream（用于读取魔数）</li>
     *   <li>调用 {@code tika.detect(stream, fileName)} 进行双维检测：
     *       <ul>
     *         <li>内容检测：读取流的前几个字节匹配魔数</li>
     *         <li>文件名辅助：以扩展名作为辅助判断依据</li>
     *       </ul>
     *   </li>
     *   <li>若 Tika 检测失败（IO 异常），降级使用 HTTP Content-Type</li>
     * </ol>
     *
     * @param file MultipartFile 文件
     * @return MIME 类型字符串（如 "application/pdf", "text/plain"）
     */
    public String detectContentType(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            return tika.detect(inputStream, file.getOriginalFilename());
        } catch (IOException e) {
            log.warn("无法检测文件类型，使用 Content-Type 头部: {}", e.getMessage());
            return file.getContentType();
        }
    }

    /**
     * 检测输入流的 MIME 类型。
     *
     * <p>注意：此方法<b>不会</b>关闭传入的 InputStream，
     * 流的生命周期由调用方管理。
     *
     * @param inputStream 文件输入流（不会被关闭）
     * @param fileName    文件名（可选，用于辅助检测；传 null 表示仅基于内容）
     * @return MIME 类型字符串；Tika 检测失败时返回 "application/octet-stream"
     */
    public String detectContentType(InputStream inputStream, String fileName) {
        try {
            return tika.detect(inputStream, fileName);
        } catch (IOException e) {
            log.warn("无法检测文件类型: {}", e.getMessage());
            return "application/octet-stream";
        }
    }

    /**
     * 检测字节数组的 MIME 类型。
     *
     * <p>适用场景：文件从 S3 下载到内存（字节数组）后需要检测类型。
     * 常用于"重新分析"流程（文件已存储，需要重新检测类型以决定解析策略）。
     *
     * <p>字节数组检测的 Tika.detect() 不会抛 IOException
     * （因为数据已在内存中），所以此方法无需 try-catch。
     *
     * @param data     文件字节数组
     * @param fileName 文件名（可选，用于辅助检测）
     * @return MIME 类型字符串
     */
    public String detectContentType(byte[] data, String fileName) {
        return tika.detect(data, fileName);
    }

    /**
     * 判断是否为 PDF 文件。
     *
     * <p>使用子串包含匹配（而非精确匹配）以适应各种 PDF MIME 变体：
     * {@code application/pdf}, {@code application/x-pdf}, {@code application/acrobat} 等。
     *
     * @param contentType MIME 类型字符串
     * @return {@code true} 是 PDF 文件
     */
    public boolean isPdf(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("pdf");
    }

    /**
     * 判断是否为 Word 文档（DOC 或 DOCX）。
     *
     * <h3>两种 Word 格式的 MIME 标识</h3>
     * <ul>
     *   <li>DOC（97-2003）：{@code application/msword}</li>
     *   <li>DOCX（2007+）：{@code application/vnd.openxmlformats-officedocument.wordprocessingml.document}
     *       （本方法仅检测是否包含 {@code wordprocessingml} 关键字）</li>
     * </ul>
     *
     * @param contentType MIME 类型字符串
     * @return {@code true} 是 Word 文档
     */
    public boolean isWordDocument(String contentType) {
        if (contentType == null) return false;
        String lower = contentType.toLowerCase();
        return lower.contains("msword") || lower.contains("wordprocessingml");
    }

    /**
     * 判断是否为纯文本文件。
     *
     * <p>匹配所有 {@code text/*} 类型的 MIME，如：
     * {@code text/plain}, {@code text/markdown}, {@code text/csv},
     * {@code text/html} 等。
     *
     * @param contentType MIME 类型字符串
     * @return {@code true} 是纯文本文件
     */
    public boolean isPlainText(String contentType) {
        return contentType != null && contentType.toLowerCase().startsWith("text/");
    }

    /**
     * 判断是否为 Markdown 文件（MIME 类型 + 文件扩展名双重检查）。
     *
     * <h3>为什么需要双重检查</h3>
     * Markdown 文件的 MIME 类型识别在不同系统上差异很大：
     * <ul>
     *   <li>有些系统报告 {@code text/markdown}</li>
     *   <li>有些系统报告 {@code text/plain}（无法区分 MD 和 TXT）</li>
     *   <li>有些系统报告 {@code application/octet-stream}</li>
     * </ul>
     * 当 MIME 类型不明确时，文件扩展名是可靠的兜底判断依据。
     *
     * <h3>检查逻辑</h3>
     * <ol>
     *   <li>先检查 MIME 类型（仅检查是否包含 markdown 或 x-markdown）</li>
     *   <li>MIME 不匹配时检查文件名扩展名（兜底）</li>
     * </ol>
     *
     * @param contentType MIME 类型字符串（可为 null）
     * @param fileName    文件名（可为 null）
     * @return {@code true} 是 Markdown 文件
     */
    public boolean isMarkdown(String contentType, String fileName) {
        if (contentType != null) {
            String lower = contentType.toLowerCase();
            if (lower.contains("markdown") || lower.contains("x-markdown")) {
                return true;
            }
        }
        if (fileName != null) {
            String lowerName = fileName.toLowerCase();
            return lowerName.endsWith(".md") ||
                    lowerName.endsWith(".markdown") ||
                    lowerName.endsWith(".mdown");
        }
        return false;
    }
}

