package com.ywy.interviewagentapplication.infrastructure.file;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * 文本清理服务：提供统一的文本内容噪音清理和规范化功能。
 *
 * <h2>定位：RAG/LLM 分析前的"保险层"</h2>
 * 从 PDF/DOCX 等文件中提取的原始文本通常包含大量噪声：
 * <ul>
 *   <li>控制字符（ASCII 0-31，如 NUL、SOH）</li>
 *   <li>嵌入图片的文件名引用（如 "image123.png"）</li>
 *   <li>图片 HTTP 链接（PDF 中的插图引用）</li>
 *   <li>临时文件路径（Tika 提取的 file:// 路径）</li>
 *   <li>符号分隔线（Markdown/纯文本中的 ---、===）</li>
 *   <li>不规则的换行符（\r\n、\r 混用）</li>
 *   <li>连续空行（PDF 多栏排版导致）</li>
 * </ul>
 * 这些噪声在 RAG 检索中会污染向量索引，在 LLM 分析中会浪费 token。
 * 本服务在文档解析和 AI 调用之间起到"文本质量保证"的作用。
 *
 * <h2>为什么用预编译正则表达式</h2>
 * Java 的 {@link Pattern} 编译是一次性开销。由于文本清理在整个请求生命周期中
 * 频繁调用（每个上传文件都要走一遍），预编译为 static final 常量可避免
 * 每次调用都重新编译正则表达式，在高并发下有显著的 CPU 节省。
 *
 * <h2>清理分层策略</h2>
 * <pre>
 * 原始文本
 *   │
 *   ├─ 第一层：语义去噪（删除不需要的内容块）
 *   │    ├─ 控制字符
 *   │    ├─ 图片文件名（整行匹配）
 *   │    ├─ 图片链接
 *   │    ├─ 文件协议路径
 *   │    └─ 符号分隔线
 *   │
 *   ├─ 第二层：格式规范化（统一排版格式）
 *   │    ├─ 统一换行符为 \n
 *   │    ├─ 去除行尾空格
 *   │    └─ 压缩连续空行（最多保留 1 个空行）
 *   │
 *   └─ 输出：干净文本
 * </pre>
 */
@Service
public class TextCleaningService {

    // ========== 预编译正则表达式（性能优化）==========

    /**
     * 匹配整行图片文件名：image数字.扩展名。
     *
     * <h3>匹配示例</h3>
     * <ul>
     *   <li>✅ image1.png</li>
     *   <li>✅ image001.jpg</li>
     *   <li>✅ image1234.webp</li>
     *   <li>❌ my_image.png（不以 "image" 开头 + 数字）</li>
     *   <li>❌ 这是一张 image1.png 图片（不是整行）</li>
     * </ul>
     *
     * <h3>为什么必须整行匹配 {@code (?m)^...$}</h3>
     * 如果不用整行锚定，可能会误删正文中的图片描述文字（如"参见 image1.png 示例"）。
     * 整行匹配确保只删除"图片文件名独占一行"的噪声行。
     */
    private static final Pattern IMAGE_FILENAME_LINE =
            Pattern.compile("(?m)^image\\d+\\.(png|jpe?g|gif|bmp|webp)\\s*$");

    /**
     * 匹配 HTTP/HTTPS 图片链接。
     *
     * <h3>匹配示例</h3>
     * <ul>
     *   <li>✅ https://example.com/photo.png</li>
     *   <li>✅ http://cdn.example.com/img/avatar.jpg?size=200</li>
     *   <li>❌ https://example.com/document.pdf（不是图片扩展名）</li>
     *   <li>❌ https://example.com（没有图片扩展名）</li>
     * </ul>
     *
     * <h3>注意</h3>
     * 此正则使用大小写不敏感匹配（{@code Pattern.CASE_INSENSITIVE}），
     * 因为 URL 中的扩展名可能是大写（.PNG）或混合大小写。
     */
    private static final Pattern IMAGE_URL =
            Pattern.compile("https?://\\S+?\\.(png|jpe?g|gif|bmp|webp)(\\?\\S*)?", Pattern.CASE_INSENSITIVE);

    /**
     * 匹配 file:// 协议 URL（如 Tika 在 PDF 解析中产出的临时文件路径）。
     *
     * <h3>背景</h3>
     * Apache Tika 在解析某些 PDF 时会将嵌入的图片/字体提取到本地临时文件，
     * 并在文本中插入 file:///tmp/tika-xxxx 路径引用。
     * 这些路径对业务分析毫无意义，需要清除。
     */
    private static final Pattern FILE_URL =
            Pattern.compile("file:(//)?\\S+", Pattern.CASE_INSENSITIVE);

    /**
     * 匹配符号分隔线（Markdown 风格）：至少 3 个连续的 {@code - _ * =}。
     *
     * <h3>匹配示例</h3>
     * <ul>
     *   <li>✅ ---</li>
     *   <li>✅ _______</li>
     *   <li>✅ ***</li>
     *   <li>✅ ==========</li>
     *   <li>❌ --（不足 3 个）</li>
     *   <li>❌ 这是---分隔线（不是整行）</li>
     * </ul>
     *
     * <h3>为什么整行匹配</h3>
     * 避免误删正文中的破折号（如 "2020--2023"）或代码块中的符号。
     */
    private static final Pattern SEPARATOR_LINE =
            Pattern.compile("(?m)^\\s*[-_*=]{3,}\\s*$");

    /**
     * 匹配不可见控制字符（ASCII 0-31，但保留换行和制表符）。
     *
     * <h3>保留字符</h3>
     * <ul>
     *   <li>{@code \n} (0x0A)：换行符——段落分隔，必须保留</li>
     *   <li>{@code \t} (0x09)：制表符——某些文档的表格/缩进依赖它</li>
     * </ul>
     *
     * <h3>删除的字符类别</h3>
     * <ul>
     *   <li>0x00-0x08：NUL, SOH, STX, ... 等控制字符</li>
     *   <li>0x0B-0x0C：垂直制表、换页</li>
     *   <li>0x0E-0x1F：SO, SI, ... 等移位/分隔控制字符</li>
     * </ul>
     */
    private static final Pattern CONTROL_CHARS =
            Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]");

    /**
     * 匹配 HTML/XML 标签（如 {@code <div>}, {@code </p>}, {@code <br/>}）。
     * <p>
     * 某些文档解析后可能残留 HTML 标签（尤其是从富文本编辑器导出的内容），
     * 此正则用于 {@link #stripHtml(String)} 方法。
     */
    private static final Pattern HTML_TAGS =
            Pattern.compile("<[^>]+>");

    /**
     * 清理和规范化文本内容。
     *
     * <h3>清理策略详解</h3>
     *
     * <h4>第一层：语义去噪</h4>
     * 删除对 RAG/LLM 无价值的噪声内容：
     * <ul>
     *   <li>控制字符：PDF 文档转换时产生的不可见字符</li>
     *   <li>图片文件名（整行）：从 DOCX 解析出的内嵌图片文件名</li>
     *   <li>图片链接：PDF 中的在线图片引用（HTTP/HTTPS）</li>
     *   <li>文件协议路径：Tika 创建的临时文件引用（file://）</li>
     *   <li>符号分隔线：Markdown 的 ---、=== 等装饰性分隔线</li>
     * </ul>
     *
     * <h4>第二层：格式规范化</h4>
     * 统一排版格式，确保输入 LLM 的文本整洁：
     * <ul>
     *   <li>换行符统一：{\@code \r\n}, {\@code \r} → {\@code \n}</li>
     *   <li>去除行尾空格（保留空行，保持段落结构）</li>
     *   <li>压缩连续空行：≥3 个换行符 → 2 个（即最多保留 1 个空行）</li>
     * </ul>
     *
     * <h3>为什么保留 1 个空行</h3>
     * 完全删除空行会导致所有文字连成一片，破坏段落结构；
     * 保留过多空行（如 PDF 多栏排版产生的 5-10 个连续空行）浪费 LLM token。
     * 折中方案：最多保留 1 个空行（即 2 个连续换行符），既保持段落分隔，
     * 又不至于浪费。
     *
     * @param text 原始文本（可能包含各种噪声）
     * @return 清理后的干净文本（不会返回 null，最差返回空字符串 ""）
     */
    public String cleanText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String t = text;

        // ========== 第一层：语义去噪 ==========
        t = CONTROL_CHARS.matcher(t).replaceAll("");
        t = IMAGE_FILENAME_LINE.matcher(t).replaceAll("");
        t = IMAGE_URL.matcher(t).replaceAll("");
        t = FILE_URL.matcher(t).replaceAll("");
        t = SEPARATOR_LINE.matcher(t).replaceAll("");

        // ========== 第二层：格式规范化 ==========
        // 统一换行符
        t = t.replace("\r\n", "\n").replace("\r", "\n");

        // 去掉行尾空格和制表符，保留空行（保持段落结构）
        t = t.replaceAll("(?m)[ \t]+$", "");

        // 压缩连续空行：最多保留 2 个换行符（即一个空行）
        t = t.replaceAll("\\n{3,}", "\n\n");

        return t.strip();
    }

    /**
     * 清理文本并限制最大长度。
     *
     * <p>适用场景：LLM 上下文窗口有限，需要在清理后截断文本。
     * 先清理再尝试截断：因为清理可能移除噪声字符，让同样长度的限制容纳更多有效信息。
     *
     * @param text      原始文本
     * @param maxLength 最大字符数
     * @return 清理后且不超过 maxLength 的文本
     */
    public String cleanTextWithLimit(String text, int maxLength) {
        String cleaned = cleanText(text);
        if (cleaned.length() > maxLength) {
            return cleaned.substring(0, maxLength);
        }
        return cleaned;
    }

    /**
     * 将文本转为单行（所有换行符替换为空格）。
     *
     * <p>适用场景：需要将文本嵌入到单行字段中（如日志、JSON 摘要、数据库单列展示）。
     * 注意：这会丢失段落结构，仅在确实需要单行展示时使用。
     *
     * @param text 原始文本
     * @return 单行文本（所有空白压缩为单个空格）
     */
    public String cleanToSingleLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        return text
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    /**
     * 移除 HTML 标签和常见 HTML 实体，返回纯文本。
     *
     * <h3>处理范围</h3>
     * <ul>
     *   <li>HTML 标签：{@code <div>}, {@code </p>}, {@code <br/>} → 替换为空格</li>
     *   <li>HTML 实体：{@code &nbsp;} → 空格, {@code &amp;} → &,
     *       {@code &lt;} → &lt;, {@code &gt;} → &gt;,
     *       {@code &quot;} → ", {@code &apos;} → '</li>
     * </ul>
     *
     * <h3>为什么标签替换为空格而非空字符串</h3>
     * 例如 {@code "Hello<div>World"} → {@code "Hello World"}（单词间有空格），
     * 而非 {@code "HelloWorld"}（单词粘在一起）。
     *
     * @param text 可能包含 HTML 的文本
     * @return 去除 HTML 标记和实体后的纯文本
     */
    public String stripHtml(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        return HTML_TAGS.matcher(text).replaceAll(" ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replaceAll("\\s+", " ")
                .strip();
    }
}

