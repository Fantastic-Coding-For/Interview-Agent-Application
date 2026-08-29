package com.ywy.interviewagentapplication.infrastructure.file;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.xml.sax.ContentHandler;

import java.io.InputStream;

/**
 * 空操作的嵌入文档提取器：用于禁用 Tika 对嵌入资源的递归解析。
 *
 * <h2>背景：为什么要禁用嵌入文档解析</h2>
 * Apache Tika 默认会递归解析文档中嵌入的所有子资源：
 * <ul>
 *   <li>PDF 中的内嵌图片 → 提取出 "embedded-image-1.png" 等文件名引用</li>
 *   <li>DOCX 中的嵌入 Excel 表格 → 提取出无格式的数字文本</li>
 *   <li>PPTX 中的嵌入视频 → 提取出二进制乱码</li>
 *   <li>PDF 中的嵌入字体文件 → 提取出无意义的字体数据</li>
 * </ul>
 * 这些嵌入资源的文本提取结果<b>对我们的业务场景毫无价值</b>，反而会：
 * <ul>
 *   <li>污染提取结果（简历正文中混入图片文件名）</li>
 *   <li>增加解析时间（递归处理每个嵌入资源）</li>
 *   <li>浪费后续 AI 分析的 token（噪声数据）</li>
 * </ul>
 *
 * <h2>实现：Template Method 模式的回调接口</h2>
 * {@link EmbeddedDocumentExtractor} 是 Tika 定义的一个 SPI 接口，
 * 通过策略模式让调用方控制嵌入资源处理行为：
 * <ol>
 *   <li>Tika 遇到嵌入资源时调用 {@link #shouldParseEmbedded(Metadata)} 询问"要处理吗？"</li>
 *   <li>本实现始终返回 {@code false}，告诉 Tika 跳过</li>
 *   <li>{@link #parseEmbedded(InputStream, ContentHandler, Metadata, boolean)}
 *       理论上不会被调用，但提供空实现作为防御</li>
 * </ol>
 *
 * <h2>使用方式</h2>
 * 在 {@code ParseContext} 中注册：
 * <pre>{@code
 * ParseContext context = new ParseContext();
 * context.set(EmbeddedDocumentExtractor.class, new NoOpEmbeddedDocumentExtractor());
 * }</pre>
 * Tika 在解析过程中通过 context 获取此实例并调用其回调方法。
 *
 * @see com.ywy.interviewagentapplication.infrastructure.file.DocumentParseService 调用方
 */
@Slf4j
public class NoOpEmbeddedDocumentExtractor implements EmbeddedDocumentExtractor {

    /**
     * 是否应该解析嵌入文档
     *
     * @param metadata 文档元数据
     * @return 始终返回 false，禁用嵌入文档解析
     */
    @Override
    public boolean shouldParseEmbedded(Metadata metadata) {
        // 记录跳过的嵌入文档（使用字符串常量，兼容不同 Tika 版本）
        String resourceName = metadata.get("resourceName");
        if (resourceName != null) {
            log.debug("Skip embedded document: {}", resourceName);
        }
        return false;
    }

    /**
     * 解析嵌入文档（空实现）
     *
     * @param stream   输入流
     * @param handler  内容处理器
     * @param metadata 元数据
     * @param outputHtml 是否输出 HTML
     */
    @Override
    public void parseEmbedded(
            InputStream stream,
            ContentHandler handler,
            Metadata metadata,
            boolean outputHtml) {
        // 空实现，不执行任何操作
        // 由于 shouldParseEmbedded 返回 false，此方法不会被调用
    }
}

