package com.ywy.interviewagentapplication.infrastructure.export;

import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.font.PdfFontFactory.EmbeddingStrategy;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewAnswerEntity;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewSessionEntity;
import com.ywy.interviewagentapplication.modules.interview.model.ResumeAnalysisResponse;
import com.ywy.interviewagentapplication.modules.resume.model.ResumeEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * PDF 导出服务。
 *
 * <h3>技术选型：为什么用 iText 而非其他方案？</h3>
 * <ul>
 *   <li><b>iText</b>（当前选型）：Java 生态中最成熟的 PDF 库，支持中文字体嵌入、
 *       表格布局、样式控制。AGPL 协议，适合内部使用。</li>
 *   <li><b>Apache PDFBox</b>：中文支持需额外配置，排版能力弱于 iText，不适合复杂报告。</li>
 *   <li><b>Flying Saucer</b>：HTML → PDF 转换，依赖 iText 作为渲染引擎。
 *       用 HTML 排版更直观但精确控制不如直接使用 iText API。</li>
 *   <li><b>Thymeleaf + Flying Saucer</b>：适合模板化报告，但引入额外依赖且
 *       调试不如纯代码控制直观。</li>
 * </ul>
 * 最终选择 iText 的核心理由：面试报告需要精确地排版（标题/表格/分页），
 * 且中文字体嵌入是刚需，iText 在这些方面是最佳选择。
 *
 * <h3>中文字体策略</h3>
 * 使用项目内嵌的朱雀仿宋（ZhuqueFangsong-Regular.ttf）字体，而非依赖系统字体：
 * <ul>
 *   <li><b>跨平台一致性</b>：不同操作系统（Linux/Windows/macOS）自带的中文字体不同，
 *       内嵌字体保证在任何环境下 PDF 的展示效果一致。</li>
 *   <li><b>FORCE_EMBEDDED 策略</b>：强制将完整字体嵌入 PDF 文件，接收方即使没有
 *       安装朱雀仿宋也能正确显示中文。代价是 PDF 文件增大 ~3-5MB。如果后续需要
 *       优化文件大小，可改为 {@code SUBSET}（只嵌入用到的字符）。</li>
 *   <li><b>IDENTITY_H 编码</b>：Identity-H 是 PDF 规范中的 CID 字体编码，
 *       支持 Unicode 全字符集，是中文 PDF 的标准做法。</li>
 * </ul>
 *
 * <h3>内存管理</h3>
 * 使用 {@link ByteArrayOutputStream} 作为 PDF 的输出目标，而非临时文件：
 * <ul>
 *   <li>避免了文件系统 I/O 和临时文件清理的复杂度</li>
 *   <li>适合典型的面试报告大小（< 10MB）</li>
 *   <li>不适合超大 PDF（如 100MB+），那种场景应改用 FileOutputStream</li>
 * </ul>
 *
 * @see "iText 7 Core PDF Library Documentation"
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfExportService {
    /** 统一日期时间格式：年-月-日 时:分:秒 */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /**
     * 标题/表头的主题蓝色（#2980B9）。
     * 选择蓝色系是因为它给人专业、可信赖的感觉，适合面试评估这类正式文档。
     */
    private static final DeviceRgb HEADER_COLOR = new DeviceRgb(41, 128, 185);
    /**
     * 章节标题的深灰色（#34495E）。
     * 比纯黑色柔和，比标题蓝色低调，在视觉上形成"标题→章节→正文"的三层层次感。
     */
    private static final DeviceRgb SECTION_COLOR = new DeviceRgb(52, 73, 94);

    private final ObjectMapper objectMapper;

    /**
     * 创建支持中文的 PDF 字体。
     *
     * <h4>异常处理策略</h4>
     * 字体加载失败直接抛 {@link BusinessException} 而非返回 null：
     * <ul>
     *   <li>没有中文字体的 PDF 导出毫无意义（会出现豆腐块或乱码）</li>
     *   <li>字体文件缺失通常是部署问题（忘记打包字体），应该在第一次导出时就暴露，
     *       而非静默生成坏文件</li>
     * </ul>
     *
     * @return 支持中文的 PdfFont 实例
     * @throws BusinessException 如果字体文件缺失或加载失败
     */
    private PdfFont createChineseFont() {
        try (var fontStream = getClass().getClassLoader().getResourceAsStream("fonts/ZhuqueFangsong-Regular.ttf")) {
            if (fontStream != null) {
                byte[] fontBytes = fontStream.readAllBytes();
                log.debug("使用项目内嵌字体: fonts/ZhuqueFangsong-Regular.ttf");
                return PdfFontFactory.createFont(fontBytes, PdfEncodings.IDENTITY_H, EmbeddingStrategy.FORCE_EMBEDDED);
            }

            log.error("未找到字体文件: fonts/ZhuqueFangsong-Regular.ttf");
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "字体文件缺失，请联系管理员");

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("创建中文字体失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "创建字体失败: " + e.getMessage());
        }
    }

    /**
     * 清理文本中可能导致 PDF 渲染问题的字符。
     *
     * <h4>为什么要清理而不仅仅是设置字体？</h4>
     * 即使嵌入了中文字体，某些 Unicode 字符类别仍然无法渲染：
     * <ul>
     *   <li>{@code \p{So}}（Symbol, Other）：Emoji 表情符号（😀🎉等），
     *       朱雀仿宋不含这些字形，会导致 PDF 中出现空白方块</li>
     *   <li>{@code \p{Cs}}（Surrogate）：UTF-16 代理对字符，如果被错误地单独出现
     *       （不配对），会导致字体渲染引擎异常</li>
     * </ul>
     * 移除这些字符比尝试渲染它们更安全——它们通常是 LLM 意外输出的非语义内容。
     *
     * @param text 原始文本
     * @return 清理后的安全文本，null 输入返回空字符串
     */
    private String sanitizeText(String text) {
        if (text == null) return "";
        // 移除可能导致问题的特殊字符（如 emoji）
        return text.replaceAll("[\\p{So}\\p{Cs}]", "").trim();
    }

    /**
     * 导出简历分析报告为 PDF。
     *
     * <h4>报告结构</h4>
     * <ol>
     *   <li>标题：简历分析报告（居中、大号、蓝色）</li>
     *   <li>基本信息：文件名 + 上传时间</li>
     *   <li>综合评分：总分 + 颜色编码（绿/黄/红）</li>
     *   <li>各维度评分：表格形式（项目经验/技能匹配/内容完整性/结构清晰度/表达专业性）</li>
     *   <li>简历摘要：LLM 生成的内容摘要</li>
     *   <li>优势亮点：列表形式</li>
     *   <li>改进建议：按优先级分类，逐条展开</li>
     * </ol>
     *
     * <h4>评分颜色编码</h4>
     * 沿用交通灯模式——用户无需阅读具体数字即可感知分数区间：
     * <ul>
     *   <li>≥80 → 绿色 → "优秀，无明显短板"</li>
     *   <li>60-79 → 黄色 → "中等，有提升空间"</li>
     *   <li>&lt;60 → 红色 → "需大幅改进"</li>
     * </ul>
     *
     * @param resume   简历实体（包含文件元信息）
     * @param analysis LLM 分析结果（包含评分、摘要、建议）
     * @return PDF 文件的字节数组，可直接写入 HTTP 响应或保存为文件
     */
    public byte[] exportResumeAnalysis(ResumeEntity resume, ResumeAnalysisResponse analysis) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdfDoc = new PdfDocument(writer);
        Document document = new Document(pdfDoc);

        // 使用支持中文的字体
        PdfFont font = createChineseFont();
        document.setFont(font);

        // 标题
        Paragraph title = new Paragraph("简历分析报告")
                .setFontSize(24)
                .setBold()
                .setTextAlignment(TextAlignment.CENTER)
                .setFontColor(HEADER_COLOR);
        document.add(title);

        // 基本信息
        document.add(new Paragraph("\n"));
        document.add(createSectionTitle("基本信息"));
        document.add(new Paragraph("文件名: " + resume.getOriginalFilename()));
        document.add(new Paragraph("上传时间: " +
                (resume.getUploadedAt() != null ? DATE_FORMAT.format(resume.getUploadedAt()) : "未知")));

        // 总分
        document.add(new Paragraph("\n"));
        document.add(createSectionTitle("综合评分"));
        Paragraph scoreP = new Paragraph("总分: " + analysis.overallScore() + " / 100")
                .setFontSize(18)
                .setBold()
                .setFontColor(getScoreColor(analysis.overallScore()));
        document.add(scoreP);

        // 各维度评分
        if (analysis.scoreDetail() != null) {
            document.add(new Paragraph("\n"));
            document.add(createSectionTitle("各维度评分"));

            Table scoreTable = new Table(UnitValue.createPercentArray(new float[]{2, 1}))
                    .useAllAvailableWidth();
            addScoreRow(scoreTable, "项目经验", analysis.scoreDetail().projectScore(), 40);
            addScoreRow(scoreTable, "技能匹配度", analysis.scoreDetail().skillMatchScore(), 20);
            addScoreRow(scoreTable, "内容完整性", analysis.scoreDetail().contentScore(), 15);
            addScoreRow(scoreTable, "结构清晰度", analysis.scoreDetail().structureScore(), 15);
            addScoreRow(scoreTable, "表达专业性", analysis.scoreDetail().expressionScore(), 10);
            document.add(scoreTable);
        }

        // 简历摘要
        if (analysis.summary() != null) {
            document.add(new Paragraph("\n"));
            document.add(createSectionTitle("简历摘要"));
            document.add(new Paragraph(sanitizeText(analysis.summary())));
        }

        // 优势亮点
        if (analysis.strengths() != null && !analysis.strengths().isEmpty()) {
            document.add(new Paragraph("\n"));
            document.add(createSectionTitle("优势亮点"));
            for (String strength : analysis.strengths()) {
                document.add(new Paragraph("• " + sanitizeText(strength)));
            }
        }

        // 改进建议
        if (analysis.suggestions() != null && !analysis.suggestions().isEmpty()) {
            document.add(new Paragraph("\n"));
            document.add(createSectionTitle("改进建议"));
            for (ResumeAnalysisResponse.Suggestion suggestion : analysis.suggestions()) {
                document.add(new Paragraph("【" + suggestion.priority() + "】" + sanitizeText(suggestion.category()))
                        .setBold());
                document.add(new Paragraph("问题: " + sanitizeText(suggestion.issue())));
                document.add(new Paragraph("建议: " + sanitizeText(suggestion.recommendation())));
                document.add(new Paragraph("\n"));
            }
        }

        document.close();
        return baos.toByteArray();
    }

    /**
     * 导出面试报告为 PDF。
     *
     * <h4>与简历分析报告的差异</h4>
     * 面试报告包含更多动态内容：
     * <ul>
     *   <li>优势/改进建议从 JSON 字段动态解析（而非来自固定结构的 response 对象）</li>
     *   <li>包含详细的问答列表，每道题显示：问题 → 回答 → 得分 → 评价 → 参考答案</li>
     *   <li>JSON 解析失败时静默降级，不阻断整体报告生成，
     *       只在日志中记录错误（因为评分详情比一段优势描述更重要）</li>
     * </ul>
     *
     * @param session 面试会话实体（包含评估结果和答案列表）
     * @return PDF 文件的字节数组
     */
    public byte[] exportInterviewReport(InterviewSessionEntity session) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdfDoc = new PdfDocument(writer);
        Document document = new Document(pdfDoc);

        // 使用支持中文的字体
        PdfFont font = createChineseFont();
        document.setFont(font);

        // 标题
        Paragraph title = new Paragraph("模拟面试报告")
                .setFontSize(24)
                .setBold()
                .setTextAlignment(TextAlignment.CENTER)
                .setFontColor(HEADER_COLOR);
        document.add(title);

        // 基本信息
        document.add(new Paragraph("\n"));
        document.add(createSectionTitle("面试信息"));
        document.add(new Paragraph("会话ID: " + session.getSessionId()));
        document.add(new Paragraph("题目数量: " + session.getTotalQuestions()));
        document.add(new Paragraph("面试状态: " + getStatusText(session.getStatus())));
        document.add(new Paragraph("开始时间: " +
                (session.getCreatedAt() != null ? DATE_FORMAT.format(session.getCreatedAt()) : "未知")));
        if (session.getCompletedAt() != null) {
            document.add(new Paragraph("完成时间: " + DATE_FORMAT.format(session.getCompletedAt())));
        }

        // 总分
        if (session.getOverallScore() != null) {
            document.add(new Paragraph("\n"));
            document.add(createSectionTitle("综合评分"));
            Paragraph scoreP = new Paragraph("总分: " + session.getOverallScore() + " / 100")
                    .setFontSize(18)
                    .setBold()
                    .setFontColor(getScoreColor(session.getOverallScore()));
            document.add(scoreP);
        }

        // 总体评价
        if (session.getOverallFeedback() != null) {
            document.add(new Paragraph("\n"));
            document.add(createSectionTitle("总体评价"));
            document.add(new Paragraph(sanitizeText(session.getOverallFeedback())));
        }

        // 优势
        if (session.getStrengthsJson() != null) {
            try {
                List<String> strengths = objectMapper.readValue(session.getStrengthsJson(),
                        new TypeReference<>() {
                        });
                if (!strengths.isEmpty()) {
                    document.add(new Paragraph("\n"));
                    document.add(createSectionTitle("表现优势"));
                    for (String s : strengths) {
                        document.add(new Paragraph("• " + sanitizeText(s)));
                    }
                }
            } catch (Exception e) {
                log.error("解析优势JSON失败: sessionId={}", session.getSessionId(), e);
            }
        }

        // 改进建议
        if (session.getImprovementsJson() != null) {
            try {
                List<String> improvements = objectMapper.readValue(session.getImprovementsJson(),
                        new TypeReference<>() {
                        });
                if (!improvements.isEmpty()) {
                    document.add(new Paragraph("\n"));
                    document.add(createSectionTitle("改进建议"));
                    for (String s : improvements) {
                        document.add(new Paragraph("• " + sanitizeText(s)));
                    }
                }
            } catch (Exception e) {
                log.error("解析改进建议JSON失败: sessionId={}", session.getSessionId(), e);
            }
        }

        // 问答详情
        List<InterviewAnswerEntity> answers = session.getAnswers();
        if (answers != null && !answers.isEmpty()) {
            document.add(new Paragraph("\n"));
            document.add(createSectionTitle("问答详情"));

            for (InterviewAnswerEntity answer : answers) {
                document.add(new Paragraph("\n"));
                document.add(new Paragraph("问题 " + (answer.getQuestionIndex() + 1) +
                        " [" + (answer.getCategory() != null ? answer.getCategory() : "综合") + "]")
                        .setBold()
                        .setFontSize(12));
                document.add(new Paragraph("Q: " + sanitizeText(answer.getQuestion())));
                document.add(new Paragraph("A: " + sanitizeText(answer.getUserAnswer() != null ? answer.getUserAnswer() : "未回答")));
                document.add(new Paragraph("得分: " + answer.getScore() + "/100")
                        .setFontColor(getScoreColor(answer.getScore())));
                if (answer.getFeedback() != null) {
                    document.add(new Paragraph("评价: " + sanitizeText(answer.getFeedback()))
                            .setItalic());
                }
                if (answer.getReferenceAnswer() != null) {
                    document.add(new Paragraph("参考答案: " + sanitizeText(answer.getReferenceAnswer()))
                            .setFontColor(new DeviceRgb(39, 174, 96)));
                }
            }
        }

        document.close();
        return baos.toByteArray();
    }

    /**
     * 创建统一的章节标题样式。
     * <p>
     * 抽离为独立方法确保所有章节标题视觉一致：
     * 字号14、加粗、深灰色、段前距10——形成文档的视觉层次骨架。
     *
     * @param title 章节标题文字
     * @return 应用了统一样式的 Paragraph 对象
     */
    private Paragraph createSectionTitle(String title) {
        return new Paragraph(title)
                .setFontSize(14)
                .setBold()
                .setFontColor(SECTION_COLOR)
                .setMarginTop(10);
    }

    /**
     * 向表格添加一行评分数据。
     *
     * <h4>maxScore 参数的必要性</h4>
     * 不同维度的满分不同（项目经验 40 分 vs 表达专业性 10 分），
     * 但分数颜色应基于百分比而非绝对值——40分制中的32分(80%)和10分制中的8分(80%)
     * 应该显示为同一种颜色。因此颜色计算使用 {@code score * 100 / maxScore}。
     *
     * @param table     目标表格
     * @param dimension 维度名称
     * @param score     实际得分
     * @param maxScore  该维度的满分值
     */
    private void addScoreRow(Table table, String dimension, int score, int maxScore) {
        table.addCell(new Cell().add(new Paragraph(dimension)));
        table.addCell(new Cell().add(new Paragraph(score + " / " + maxScore)
                .setFontColor(getScoreColor(score * 100 / maxScore))));
    }

    /**
     * 根据分数百分比返回对应的颜色。
     *
     * <h4>交通灯颜色系统</h4>
     * <ul>
     *   <li>绿（#27AE60）：≥80%，表现优秀</li>
     *   <li>黄（#F1C40F）：60-79%，表现中等，有提升空间</li>
     *   <li>红（#E74C3C）：&lt;60%，需要重点关注</li>
     * </ul>
     * 这些颜色与 iText 的 {@link DeviceRgb} 配合使用，确保在屏幕和打印时都清晰可辨。
     * <p>
     * 注意：红色使用 #E74C3C 而非纯红 #FF0000——降低了视觉侵略性，
     * 在打印的评估报告中看起来更专业。
     *
     * @param score 分数（0-100）
     * @return 对应的颜色对象
     */
    private DeviceRgb getScoreColor(int score) {
        if (score >= 80) return new DeviceRgb(39, 174, 96);   // 绿色
        if (score >= 60) return new DeviceRgb(241, 196, 15);  // 黄色
        return new DeviceRgb(231, 76, 60);                    // 红色
    }

    /**
     * 将 SessionStatus 枚举转换为中文展示文本。
     * <p>
     * 使用 Java 14+ 的 switch 表达式（arrow syntax），
     * 覆盖所有枚举值——如果新增状态而未更新此方法，编译器会报错（增强安全性）。
     *
     * @param status 会话状态枚举
     * @return 对应的中文展示文本
     */
    private String getStatusText(InterviewSessionEntity.SessionStatus status) {
        return switch (status) {
            case CREATED -> "已创建";
            case IN_PROGRESS -> "进行中";
            case COMPLETED -> "已完成";
            case EVALUATED -> "已评估";
        };
    }
}

