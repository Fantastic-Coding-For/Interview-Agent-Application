package com.ywy.interviewagentapplication.infrastructure.file;

import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 通用文档解析服务：使用 Apache Tika 文档内容提取为字符串文本（额外处理噪音，规范格式）。
 *
 * <h2>背景：为什么用 Apache Tika</h2>
 * Apache Tika 是一个内容检测和分析工具包，能从上千种文件格式中提取文本和元数据。
 * 它内部集成了 PDFBox（PDF）、POI（Office）、以及各种纯文本解析器，
 * 对外提供统一的 {@code parse()} 接口，调用方无需关心具体格式的实现细节。
 * 相当于一个"文件格式适配器"，屏蔽了底层解析器的差异。
 *
 * <h2>架构定位</h2>
 * 本服务是<b>通用基础设施</b>，它只负责"从文件流中提取文字"，不负责：
 * <ul>
 *   <li>文本语义分析（那是 AI 调用方的事）</li>
 *   <li>结构化信息提取（那是 ResumeParseService 的事）</li>
 *   <li>文件存储（那是 FileStorageService 的事）</li>
 * </ul>
 *
 * <h2>性能优化策略</h2>
 * <ol>
 *   <li><b>禁用嵌入文档解析：</b>PDF/DOCX 中的图片、附件等嵌入资源不提取，
 *       避免解析出无意义的图片引用路径</li>
 *   <li><b>文本长度上限 5MB：</b>通过 {@link BodyContentHandler} 的构造参数限制，
 *       防止超大文件导致 OOM</li>
 *   <li><b>文本清理后处理：</b>解析结果经 {@link TextCleaningService} 过滤，
 *       去除控制字符、图片引用等噪声</li>
 *   <li><b>PDF 按坐标排序：</b>{@code setSortByPosition(true)} 确保
 *       多栏布局的 PDF 文本按阅读顺序输出（而非按 PDF 内部存储顺序）</li>
 * </ol>
 *
 * @see TextCleaningService 文本清理服务
 * @see NoOpEmbeddedDocumentExtractor 空操作嵌入文档提取器
 */
@Slf4j
@Service
public class DocumentParseService {

    /**
     * 解析文本的最大长度限制（5MB）。
     * <p>
     * 对于简历和知识库文档，5MB 纯文本已经远超实际需求
     * （一本《三体》约 20 万字，仅约 0.6MB）。
     * 设置上限是防御性措施：防止某些 PDF 包含嵌入字体/二进制流
     * 被误解析为文本导致 OOM。
     */
    private static final int MAX_TEXT_LENGTH = 5 * 1024 * 1024; // 5MB

    private final TextCleaningService textCleaningService;

    public DocumentParseService(TextCleaningService textCleaningService) {
        this.textCleaningService = textCleaningService;
    }

    /**
     * 解析上传的文件（MultipartFile），提取文本内容（额外处理噪音，规范格式）。
     *
     * <h3>执行流程</h3>
     * <ol>
     *   <li>空文件检查（防御性编程）</li>
     *   <li>从 MultipartFile 获取输入流</li>
     *   <li>调用 Tika 核心解析方法</li>
     *   <li>对解析结果执行文本清理（去噪 + 规范化）</li>
     * </ol>
     *
     * @param file 上传的文件（支持 PDF、DOCX、DOC、TXT、MD 等）
     * @return 清理后的文本内容（文件为空时返回空字符串 ""）
     * @throws BusinessException 若解析过程发生 IO/Tika/SAX 异常
     */
    public String parseContent(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        log.info("开始解析文件: {}", fileName);

        // 处理空文件
        if (file.isEmpty() || file.getSize() == 0) {
            log.warn("文件为空: {}", fileName);
            return "";
        }

        try (InputStream inputStream = file.getInputStream()) {
            String content = parseContent(inputStream);
            String cleanedContent = textCleaningService.cleanText(content);
            log.info("文件解析成功，提取文本长度: {} 字符", cleanedContent.length());
            return cleanedContent;
        } catch (IOException | TikaException | SAXException e) {
            log.error("文件解析失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件解析失败: " + e.getMessage());
        }
    }

    /**
     * 解析字节数组形式的文件，提取文本内容（额外处理噪音，规范格式）。
     *
     * <p>与 {@link #parseContent(MultipartFile)} 的唯一区别：输入源是字节数组而非 MultipartFile。
     *
     * @param fileBytes 文件字节数组
     * @param fileName  原始文件名（仅用于日志，不影响解析逻辑）
     * @return 清理后的文本内容
     * @throws BusinessException 若解析失败
     */
    public String parseContent(byte[] fileBytes, String fileName) {
        log.info("开始解析文件（从字节数组）: {}", fileName);

        // 处理空文件
        if (fileBytes == null || fileBytes.length == 0) {
            log.warn("文件字节数组为空: {}", fileName);
            return "";
        }

        try (InputStream inputStream = new ByteArrayInputStream(fileBytes)) {
            String content = parseContent(inputStream);
            String cleanedContent = textCleaningService.cleanText(content);
            log.info("文件解析成功，提取文本长度: {} 字符", cleanedContent.length());
            return cleanedContent;
        } catch (IOException | TikaException | SAXException e) {
            log.error("文件解析失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件解析失败: " + e.getMessage());
        }
    }

    /**
     * 核心解析方法：使用显式 Parser + Context 方式解析文档流。
     *
     * <h3>为什么用显式 Parser + Context 而非简单的 {@code Tika().parseToString()}</h3>
     * {@code new Tika().parseToString()} 虽然代码更简洁，但：
     * <ul>
     *   <li>无法禁用嵌入文档解析（会提取出无意义的图片路径）</li>
     *   <li>无法配置 PDF 解析参数（多栏排序、图片开关等）</li>
     *   <li>无法注入自定义的 {@link EmbeddedDocumentExtractor}</li>
     * </ul>
     * 显式构建 Parser + Context 提供了<b>精细控制能力</b>，代码量略多但解析质量远优于默认行为。
     *
     * <h3>各组件说明</h3>
     * <table border="1">
     *   <tr><th>组件</th><th>作用</th></tr>
     *   <tr><td>AutoDetectParser</td><td>自动检测文件格式并选择合适的子解析器</td></tr>
     *   <tr><td>BodyContentHandler</td><td>只收集正文文本内容，限制最大长度防止 OOM</td></tr>
     *   <tr><td>Metadata</td><td>接收文件元数据（作者、创建时间等，当前未使用）</td></tr>
     *   <tr><td>ParseContext</td><td>传递解析器配置和自定义组件</td></tr>
     *   <tr><td>NoOpEmbeddedDocumentExtractor</td><td>禁用嵌入资源解析</td></tr>
     *   <tr><td>PDFParserConfig</td><td>PDF 解析专用优化配置</td></tr>
     * </table>
     *
     * @param inputStream 文件输入流（由调用方负责关闭）
     * @return 提取的文本内容
     * @throws IOException    IO 异常（读取流失败）
     * @throws TikaException  Tika 解析异常（格式不支持/损坏文件）
     * @throws SAXException   SAX 内容处理异常
     */
    private String parseContent(InputStream inputStream) throws IOException, TikaException, SAXException {
        // 1. 创建自动检测解析器
        AutoDetectParser parser = new AutoDetectParser();

        // 2. 创建内容处理器，只接收正文，限制最大长度为 5MB
        BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_LENGTH);

        // 3. 创建元数据对象
        Metadata metadata = new Metadata();

        // 4. 创建解析上下文
        ParseContext context = new ParseContext();

        // 5. 显式指定 Parser 到 Context（增强健壮性）
        context.set(Parser.class, parser);

        // 6. 禁用嵌入文档解析（关键：避免提取图片引用和临时文件路径）
        context.set(EmbeddedDocumentExtractor.class, new NoOpEmbeddedDocumentExtractor());

        // 7. PDF 解析专用配置：关闭图片提取，按位置排序文本
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(false);
        pdfConfig.setSortByPosition(true); // 按 x/y 坐标排序文本，改善多栏布局解析顺序
        // 注意：Tika 2.9.2 中 setExtractAnnotations 方法可能不存在，关闭图片提取已足够
        context.set(PDFParserConfig.class, pdfConfig);

        // 8. 执行解析
        parser.parse(inputStream, handler, metadata, context);

        // 9. 返回提取的文本内容
        return handler.toString();
    }

    /**
     * 组合操作：从 S3 下载文件并解析为字符串文本（额外处理噪音，规范格式）。
     *
     * <h3>为什么单独封装此方法</h3>
     * 批量处理场景（如重新分析历史简历）中，
     * "下载 → 解析" 是一个高频组合操作。封装后调用方只需一行代码，
     * 且异常处理逻辑统一。
     *
     * <h3>异常传播策略</h3>
     * <ul>
     *   <li>{@link BusinessException}：直接向上抛（保留原始语义）</li>
     *   <li>其他 {@link Exception}：包装为 BusinessException 统一向上抛</li>
     * </ul>
     *
     * @param storageService   文件存储服务（用于下载）
     * @param storageKey       文件在 S3 中的存储键
     * @param originalFilename 原始文件名（用于日志和 MIME 检测辅助）
     * @return 清理后的文本内容
     * @throws BusinessException 若下载失败或解析失败
     */
    public String downloadAndParseContent(FileStorageService storageService, String storageKey, String originalFilename) {
        try {
            byte[] fileBytes = storageService.downloadFile(storageKey);
            if (fileBytes == null || fileBytes.length == 0) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "下载文件失败");
            }
            return parseContent(fileBytes, originalFilename);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("下载并解析文件失败: storageKey={}, error={}", storageKey, e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "下载并解析文件失败: " + e.getMessage());
        }
    }
}

