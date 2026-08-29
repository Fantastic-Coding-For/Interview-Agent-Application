package com.ywy.interviewagentapplication.common.evaluation;

import com.ywy.interviewagentapplication.common.ai.StructuredOutputInvoker;
import com.ywy.interviewagentapplication.common.evaluation.EvaluationReport.CategoryScore;
import com.ywy.interviewagentapplication.common.evaluation.EvaluationReport.QuestionEvaluation;
import com.ywy.interviewagentapplication.common.evaluation.EvaluationReport.ReferenceAnswer;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 面试评估服务：文字面试和语音面试共用的评估引擎。
 *
 * <h3>评估流水线（Pipeline）</h3>
 * <pre>
 *   QaRecord 列表
 *       │
 *       ▼
 * ┌─────────────────┐
 * │ 阶段1: 分批评估     ← 将题目按 batchSize 分组，每组独立调用 LLM
 * │ (evaluateInBatches)    每批返回 BatchReportDTO（评分+反馈+优缺+参考答案）
 * └────────┬────────┘
 *          │ List of BatchResult
 *          ▼
 * ┌─────────────────┐
 * │ 阶段2: 合并批次     ← 将各批次的逐题拼接、优缺点去重合并、反馈拼接
 * │ (merge*)              此阶段不调用 LLM，纯 Java 操作
 * └────────┬────────┘
 *          │ 合并后的中间结果 + 兜底值
 *          ▼
 * ┌─────────────────┐
 * │ 阶段3: 二次汇总     ← 将合并结果再次交给 LLM，站在全局视角整合、去重、消解矛盾
 * │ (summarizeBatch)      如果 LLM 调用失败，降级使用阶段2的合并结果作为兜底
 * └────────┬────────┘
 *          │ SummaryDTO
 *          ▼
 * ┌─────────────────┐
 * │ 阶段4: 构建报告     ← 将逐题评分 + 全局总结组装为 EvaluationReport
 * │ (buildReport)         计算分类平均分、整体平均分
 * └─────────────────┘
 * </pre>
 *
 * <h3>为什么需要"分批评估 + 二次汇总"的两阶段 LLM 调用？</h3>
 * <ol>
 *   <li><b>注意力稀释问题</b>：当面试有 20+ 道题时，一次性输入所有问答
 *       会导致 LLM 关注开头和结尾而忽略中间部分（"Lost in the Middle"现象）。</li>
 *   <li><b>评分一致性</b>：分批评分在同一个小批次内保持相对标准一致（同一批内的
 *       题目被同一个 LLM 调用评估），但不同批次的标准可能漂移。二次汇总阶段
 *       可以检测并纠正这种漂移。</li>
 *   <li><b>去重和整合</b>：每个批次独立生成了 strengths/improvements，
 *       可能存在重复或矛盾。二次汇总让 LLM 从全局视角做整合。</li>
 * </ol>
 *
 * <h3>降级策略（Graceful Degradation）</h3>
 * 整个评估流程设计为<b>永不失败</b>：即使 LLM 调用全部失败，也会返回一份可用的评估报告（所有题目 0 分 + 兜底评语）。这是因为：
 * <ul>
 *   <li>面试是一个"收费"流程，如果候选人完成了面试却因为评估失败而看不到报告，体验极差</li>
 *   <li>0 分报告明确传达了"评估未完成"的信息</li>
 * </ul>
 * 降级层级：
 * <ol>
 *   <li>LLM 单批失败 → 该批返回 null，合并时填充 0 分 + "未生成评估"的占位数据</li>
 *   <li>二次汇总 LLM 失败 → 使用阶段2的聚合结果作为最终报告</li>
 * </ol>
 *
 * @see EvaluationReport 输出的评估报告
 * @see QaRecord 输入的问答记录单元
 * @see InterviewEvaluationProperties 评估批次大小等配置
 */
@Service
public class UnifiedEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedEvaluationService.class);
    /**
     * 参考基线文本的最大长度限制（6000 字符）。
     * <p>
     * 参考基线是知识库中与面试相关的文档内容，用于让 LLM 参考"标准答案"。
     * 限制为 6000 字符（约 3000 tokens）是为了：
     * <ul>
     *   <li>控制单次 LLM 调用的 token 消耗（与问答记录、提示词一起保持在合理范围内）</li>
     *   <li>防止无关内容稀释评估质量：长参考文本中可能有大量不相关内容</li>
     * </ul>
     */
    private static final int MAX_REFERENCE_CONTEXT_CHARS = 6000;

    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    /** 将 LLM JSON 输出反序列化为 BatchReportDTO 的转换器 */
    private final BeanOutputConverter<BatchReportDTO> outputConverter;
    private final PromptTemplate summarySystemPromptTemplate;
    private final PromptTemplate summaryUserPromptTemplate;
    /** 将 LLM JSON 输出反序列化为 SummaryDTO 的转换器 */
    private final BeanOutputConverter<SummaryDTO> summaryOutputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final int evaluationBatchSize;
    /**
     * Spring 资源加载器：用于读取 classpath 下的提示词模板文件。
     * 注入 ResourceLoader 而非直接使用 Files.readString() 的原因是：
     * classpath 资源在 jar 包内，运行时必须通过 ResourceLoader 获取 InputStream，
     * 直接文件路径访问在 jar 环境中会失败。
     */
    private final ResourceLoader resourceLoader;

    /**
     * LLM 一轮批次评估结果 DTO。
     */
    private record BatchReportDTO(
            int overallScore,
            String overallFeedback,
            List<String> strengths,
            List<String> improvements,
            List<QuestionEvalDTO> questionEvaluations
    ) {}

    /**
     * LLM 对 单道题的评估结果 DTO。
     */
    private record QuestionEvalDTO(
            /** 问题序号（0-based），与 QaRecord 对应 */
            int questionIndex,
            /** 该题的评分（0-100） */
            int score,
            /** LLM 的评估反馈（解释为什么给这个分数） */
            String feedback,
            /** LLM 生成的参考答案 */
            String referenceAnswer,
            /** 关键要点列表 */
            List<String> keyPoints
    ) {}

    /**
     * LLM 一轮批次评估结果的封装（额外包装了批次范围，用于第二轮评估前的合并）。
     * <p>startIndex：从 0 开始，该批次的第一个问题在整个列表中的索引</p>
     * <p>endIndex：该批次的最后一个问题在整个列表中的索引 + 1</p>
     * 批次对应索引为：[start, end)
     */
    private record BatchResult(
            int startIndex,
            int endIndex,
            BatchReportDTO report
    ) {}

    /**
     * LLM 二次汇总评估 DTO。
     * <p>
     * 二次汇总只关心整体层面（总评+优缺），不包含逐题评分。
     */
    private record SummaryDTO(
            /** 全局视角的总体评价（去除了批次间的冗余和矛盾） */
            String overallFeedback,
            /** 全局视角的优势列表（已去重、整合） */
            List<String> strengths,
            /** 全局视角的改进建议列表（已去重、整合） */
            List<String> improvements
    ) {}

    public UnifiedEvaluationService(
            StructuredOutputInvoker structuredOutputInvoker,
            ResourceLoader resourceLoader,
            InterviewEvaluationProperties evaluationProperties) throws IOException {
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.resourceLoader = resourceLoader;
        this.systemPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getSystemPromptPath()));
        this.userPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getUserPromptPath()));
        this.outputConverter = new BeanOutputConverter<>(BatchReportDTO.class);
        this.summarySystemPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getSummarySystemPromptPath()));
        this.summaryUserPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getSummaryUserPromptPath()));
        this.summaryOutputConverter = new BeanOutputConverter<>(SummaryDTO.class);
        this.evaluationBatchSize = Math.max(1, evaluationProperties.getBatchSize());
    }

    /**
     * 评估面试问答（无参考基线版本）。
     * <p>
     * 委托给带 referenceContext 的重载版本，传入 null 表示无参考基线。
     *
     * @param chatClient LLM 客户端（Spring AI ChatClient）
     * @param sessionId  会话ID（仅用于日志追踪，不参与业务逻辑）
     * @param qaRecords  问答记录列表（按 questionIndex 排序）
     * @param resumeText 简历文本（可为 null）
     * @return 包含全面评估结果的 EvaluationReport
     */
    public EvaluationReport evaluate(ChatClient chatClient,
                                     String sessionId,
                                     List<QaRecord> qaRecords,
                                     String resumeText) {
        return evaluate(chatClient, sessionId, qaRecords, resumeText, null);
    }

    /**
     * 评估面试，并返回评估报告（完整版，支持参考基线）。
     *
     * <h4>输入长度控制</h4>
     * 在评估开始前对两个长文本做截断（保留头部）：
     * <ul>
     *   <li><b>简历文本</b>：截断到 3000 字符</li>
     *   <li><b>参考基线</b>：截断到 {@value #MAX_REFERENCE_CONTEXT_CHARS} 字符</li>
     * </ul>
     *
     * @param chatClient       LLM 客户端
     * @param sessionId        会话ID（用于日志）
     * @param qaRecords        问答记录列表
     * @param resumeText       简历内容（可为 null）
     * @param referenceContext 知识库参考基线（可为 null）
     * @return 评估报告
     */
    public EvaluationReport evaluate(ChatClient chatClient,
                                     String sessionId,
                                     List<QaRecord> qaRecords,
                                     String resumeText,
                                     String referenceContext) {
        log.info("开始评估面试: sessionId={}, 共{}题", sessionId, qaRecords.size());

        String resumeContext = resumeText != null ? resumeText : "";
        // 超长简历截断，保留前 3000 字符（约 1500~2000 tokens），避免极端情况下 token 消耗过大
        if (resumeContext.length() > 3000) {
            resumeContext = resumeContext.substring(0, 3000) + "\n...(简历内容过长，已截断)";
        }
        String referenceBaseline = referenceContext != null ? referenceContext.trim() : "";
        if (referenceBaseline.length() > MAX_REFERENCE_CONTEXT_CHARS) {
            referenceBaseline = referenceBaseline.substring(0, MAX_REFERENCE_CONTEXT_CHARS)
                    + "\n...(参考基线过长，已截断)";
        }

        // 将整个回答列表分批，按批次通过 LLM 评估
        List<BatchResult> batchResults = evaluateInBatches(
                chatClient, sessionId, resumeContext, qaRecords, referenceBaseline
        );

        // 合并批次评估结果
        // 1. 合并各批次题目评估结果
        List<QuestionEvalDTO> mergedEvaluations = mergeQuestionEvaluations(batchResults);
        // 2. 合并各批次评语
        String fallbackFeedback = mergeOverallFeedback(batchResults);
        // 3. 合并各批次优势列表
        List<String> fallbackStrengths = mergeListItems(batchResults, true);
        // 4. 合并各批次改进建议列表
        List<String> fallbackImprovements = mergeListItems(batchResults, false);

        // 将所有合并结果进行二次汇总
        SummaryDTO summary = summarizeBatchResults(
                chatClient, sessionId, resumeContext, referenceBaseline, qaRecords,
                mergedEvaluations, fallbackFeedback, fallbackStrengths, fallbackImprovements
        );

        return buildReport(sessionId, qaRecords, mergedEvaluations,
                summary.overallFeedback(), summary.strengths(), summary.improvements());
    }

    /**
     * 从 classpath 加载提示词模板文件内容。
     *
     * @param path 资源路径（如 "classpath:prompts/interview-evaluation-system.st"）
     * @return 文件内容字符串（UTF-8 编码）
     * @throws IOException 如果文件不存在或不可读
     */
    private String loadPrompt(String path) throws IOException {
        Resource resource = resourceLoader.getResource(path);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 将问答记录按 batchSize 分批，每批独立调用 LLM 评估（顺序执行）。
     * <p>
     * 因为：
     * <ul>
     *   <li>典型面试 10-20 题，每批 8 题 → 2-3 个批次，并行意义不大</li>
     *   <li>顺序执行保证日志可追踪性（sessionId 始终对应同一个时间线）</li>
     *   <li>避免了并发调用 LLM API 时的 rate limit 问题</li>
     * </ul>
     *
     * @param chatClient       LLM 客户端
     * @param sessionId        会话ID
     * @param resumeContext    截断后的简历文本
     * @param qaRecords        完整问答记录列表
     * @param referenceContext 截断后的参考基线
     * @return 按顺序排列的批次评估结果列表
     */
    private List<BatchResult> evaluateInBatches(ChatClient chatClient, String sessionId,
                                                String resumeContext, List<QaRecord> qaRecords,
                                                String referenceContext) {
        List<BatchResult> results = new ArrayList<>();
        for (int start = 0; start < qaRecords.size(); start += evaluationBatchSize) {
            int end = Math.min(start + evaluationBatchSize, qaRecords.size());
            List<QaRecord> batch = qaRecords.subList(start, end);
            BatchReportDTO report = evaluateBatch(chatClient, sessionId, resumeContext, referenceContext, batch);
            results.add(new BatchResult(start, end, report));
        }
        return results;
    }

    /**
     * 评估单个批次：构造提示词 → 调用 LLM → 获取结构化输出。
     * <p>
     * <b>提示词构造</b>：
     * 将 BeanOutputConverter 的格式说明（JSON Schema）拼接到系统提示词末尾，
     * 告诉 LLM 以符合该 Schema 的 JSON 格式输出。Spring AI 的 BeanOutputConverter
     * 负责将 JSON 反序列化为 Java 对象。
     * <p>
     * <b>失败处理</b>：
     * 如果 LLM 调用失败（网络超时、模型不可用等），返回 null 而非抛异常。
     * 上层 merge 逻辑会将 null 转换为 0 分 + "未成功生成评估结果"的占位数据。
     * 这样单个批次的失败不会破坏整个评估报告。
     *
     * @param chatClient       LLM 客户端
     * @param sessionId        会话 ID
     * @param resumeContext    简历文本
     * @param referenceContext 参考基线
     * @param batch            本批次的问答记录
     * @return LLM 返回的批次评估结果，失败时返回 null
     */
    private BatchReportDTO evaluateBatch(ChatClient chatClient, String sessionId,
                                         String resumeContext, String referenceContext,
                                         List<QaRecord> batch) {
        String qaRecords = buildQARecords(batch);
        String systemPrompt = systemPromptTemplate.render();

        Map<String, Object> variables = new HashMap<>();
        variables.put("resumeText", resumeContext);
        variables.put("qaRecords", qaRecords);
        variables.put("referenceContext",
                (referenceContext != null && !referenceContext.isBlank()) ? referenceContext : "无");
        String userPrompt = userPromptTemplate.render(variables);

        String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();
        try {
            return structuredOutputInvoker.invoke(
                    chatClient, systemPromptWithFormat, userPrompt, outputConverter,
                    ErrorCode.INTERVIEW_EVALUATION_FAILED, "批次评估失败：", "批次评估", log
            );
        } catch (Exception e) {
            log.error("批次评估失败: sessionId={}, batchSize={}, error={}",
                    sessionId, batch.size(), e.getMessage(), e);
            // 返回空报告，让合并逻辑用零分兜底
            return null;
        }
    }

    /**
     * 格式化问答记录，用于 LLM 提示词。
     * <p>
     * 格式示例：
     * <pre>
     * 问题1 [项目经验]: 请介绍一下你的项目经验
     * 回答: 我曾经负责过...
     *
     * 问题2 [技术能力]: ...
     * 回答: (未回答)
     * </pre>
     * "(未回答)" 标记与 null 值明确区分，让 LLM 知道这是"被跳过了"而非"回答是空字符串"。
     *
     * @param batch 本批次的问答记录
     * @return 格式化的问答文本
     */
    private String buildQARecords(List<QaRecord> batch) {
        StringBuilder sb = new StringBuilder();
        for (QaRecord q : batch) {
            sb.append(String.format("问题%d [%s]: %s\n",
                    q.questionIndex() + 1, q.category(), q.question()));
            sb.append(String.format("回答: %s\n\n",
                    q.userAnswer() != null ? q.userAnswer() : "(未回答)"));
        }
        return sb.toString();
    }

    /**
     * 合并各批次的题目评估结果。
     * @param batchResults 各批次的评估结果（可能包含 null report）
     * @return 与原始 QaRecord 一一对应的评估结果列表
     */
    private List<QuestionEvalDTO> mergeQuestionEvaluations(List<BatchResult> batchResults) {
        List<QuestionEvalDTO> merged = new ArrayList<>();
        for (BatchResult result : batchResults) {
            int expectedSize = result.endIndex() - result.startIndex();
            List<QuestionEvalDTO> current =
                    result.report() != null && result.report().questionEvaluations() != null
                            ? result.report().questionEvaluations()
                            : List.of();
            for (int i = 0; i < expectedSize; i++) {
                if (i < current.size() && current.get(i) != null) {
                    merged.add(current.get(i));
                } else {
                    merged.add(new QuestionEvalDTO(
                            result.startIndex() + i, 0,
                            "该题未成功生成评估结果，系统按 0 分处理。", "", List.of()
                    ));
                }
            }
        }
        return merged;
    }

    /**
     * 合并各批次的总体评语（拼接而非覆盖）。
     * <p>
     * 使用双换行符（\n\n）拼接各批次的评语。
     * 在二次汇总阶段，LLM 可以看到所有批次的原始评语并在此基础上综合。
     * 如果所有批次都没有有效评语，返回一条兜底文案。
     *
     * @param batchResults 各批次的评估结果
     * @return 拼接后的总评语文本
     */
    private String mergeOverallFeedback(List<BatchResult> batchResults) {
        String feedback = batchResults.stream()
                .map(BatchResult::report)
                .filter(r -> r != null && r.overallFeedback() != null && !r.overallFeedback().isBlank())
                .map(BatchReportDTO::overallFeedback)
                .collect(Collectors.joining("\n\n"));
        return feedback.isBlank() ? "本次面试已完成分批评估，但未生成有效综合评语。" : feedback;
    }

    /**
     * 合并各批次的优势/改进列表（去重 + 去空白 + 上限控制）。
     * <p>{@code strengthsMode} 参数在 boolean 语义上的选择：true = 优势，false = 改进。</p>
     *
     * @param batchResults  各批次的评估结果
     * @param strengthsMode true 合并 strengths，false 合并 improvements
     * @return 去重后的列表（最多 8 条）
     */
    private List<String> mergeListItems(List<BatchResult> batchResults, boolean strengthsMode) {
        Set<String> merged = new LinkedHashSet<>();
        for (BatchResult result : batchResults) {
            BatchReportDTO report = result.report();
            if (report == null) continue;
            List<String> items = strengthsMode ? report.strengths() : report.improvements();
            if (items == null) continue;
            items.stream()
                    .filter(item -> item != null && !item.isBlank())
                    .map(String::trim)
                    .forEach(merged::add);
        }
        return merged.stream().limit(8).toList();
    }

    /**
     * 二次汇总：将各批次合并结果交给 LLM 做全局整合。
     * <p>
     * <b>降级处理</b>：
     * 如果二次汇总 LLM 调用失败（超时、模型不可用等），使用阶段2的合并结果作为最终输出——
     * 合并结果虽然缺少全局整合，但至少包含了所有批次的逐题评分。
     * 二次汇总失败不应导致整个评估流程失败。
     *
     * @param chatClient          LLM 客户端
     * @param sessionId           会话ID
     * @param resumeContext       简历内容
     * @param referenceContext    参考基线
     * @param qaRecords           完整问答记录
     * @param evaluations         合并后的逐题评分
     * @param fallbackFeedback    合并后的兜底评语
     * @param fallbackStrengths   合并后的兜底优势
     * @param fallbackImprovements 合并后的兜底改进建议
     * @return 汇总结果（优先 LLM 输出，降级使用合并结果）
     */
    private SummaryDTO summarizeBatchResults(
            ChatClient chatClient, String sessionId, String resumeContext, String referenceContext,
            List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations,
            String fallbackFeedback, List<String> fallbackStrengths, List<String> fallbackImprovements) {
        try {
            String summarySystem = summarySystemPromptTemplate.render();
            Map<String, Object> vars = new HashMap<>();
            vars.put("resumeText", resumeContext);
            vars.put("referenceContext",
                    (referenceContext != null && !referenceContext.isBlank()) ? referenceContext : "无");
            vars.put("categorySummary", buildCategorySummary(qaRecords, evaluations));
            vars.put("questionHighlights", buildQuestionHighlights(qaRecords, evaluations));
            vars.put("fallbackOverallFeedback", fallbackFeedback);
            vars.put("fallbackStrengths", String.join("\n", fallbackStrengths));
            vars.put("fallbackImprovements", String.join("\n", fallbackImprovements));
            String summaryUser = summaryUserPromptTemplate.render(vars);

            String systemWithFormat = summarySystem + "\n\n" + summaryOutputConverter.getFormat();
            SummaryDTO dto = structuredOutputInvoker.invoke(
                    chatClient, systemWithFormat, summaryUser, summaryOutputConverter,
                    ErrorCode.INTERVIEW_EVALUATION_FAILED, "总结评估失败：", "总结评估", log
            );

            String feedback = dto != null && dto.overallFeedback() != null && !dto.overallFeedback().isBlank()
                    ? dto.overallFeedback() : fallbackFeedback;
            List<String> strengths = sanitizeItems(dto != null ? dto.strengths() : null, fallbackStrengths);
            List<String> improvements = sanitizeItems(dto != null ? dto.improvements() : null, fallbackImprovements);
            return new SummaryDTO(feedback, strengths, improvements);
        } catch (Exception e) {
            log.warn("二次汇总评估失败，降级到批次聚合结果: sessionId={}, error={}", sessionId, e.getMessage());
            return new SummaryDTO(fallbackFeedback, fallbackStrengths, fallbackImprovements);
        }
    }

    /**
     * 净化列表数据。
     * <p>
     * 对列表进行过滤（去 null、去空白、trim）、去重、上限 8 条。
     * 这一层处理是防御性的，因为 LLM 可能返回空列表、含 null 元素的列表、
     * 或包含超长字符串的列表。
     *
     * @param primary  主值（LLM 返回的优先数据）
     * @param fallback 兜底值（批次合并结果）
     * @return 净化后的列表
     */
    private List<String> sanitizeItems(List<String> primary, List<String> fallback) {
        List<String> source = (primary != null && !primary.isEmpty()) ? primary : fallback;
        if (source == null || source.isEmpty()) return List.of();
        return source.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim).distinct().limit(8).toList();
    }

    /**
     * 构建最终的评估报告。
     * <p>
     * <b>评分计算逻辑</b>：
     * <ol>
     *   <li><b>逐题评分</b>：已回答 → LLM 给的分数；未回答 → 0 分</li>
     *   <li><b>分类评分</b>：按 category 分组，计算组内平均分</li>
     *   <li><b>整体评分</b>：所有已回答题目分数的算术平均（未回答的不参与平均）</li>
     * </ol>
     * <p>
     * <b>为什么整体评分不包括未回答的题目？</b>
     * 未回答的题目给 0 分并拉低平均分是不公平的。候选人可能是被面试官跳过了某题
     * 而非"答错了"。整体评分反映的是"已展示的能力水平"，而非"完成度"。
     * 完成度信息通过 totalQuestions 和 answeredCount 的对比来传达。
     *
     * @param sessionId       会话 ID
     * @param qaRecords       原始问答记录
     * @param evaluations     逐题评估结果（与 qaRecords 一一对应）
     * @param overallFeedback 总体评价
     * @param strengths       优势列表
     * @param improvements    改进列表
     * @return 完整的评估报告
     */
    private EvaluationReport buildReport(String sessionId, List<QaRecord> qaRecords,
                                         List<QuestionEvalDTO> evaluations,
                                         String overallFeedback,
                                         List<String> strengths, List<String> improvements) {
        List<QuestionEvaluation> questionDetails = new ArrayList<>();
        List<ReferenceAnswer> referenceAnswers = new ArrayList<>();
        Map<String, List<Integer>> categoryScoresMap = new HashMap<>();

        long answeredCount = qaRecords.stream()
                .filter(q -> q.userAnswer() != null && !q.userAnswer().isBlank())
                .count();

        int evalSize = evaluations != null ? evaluations.size() : 0;

        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evalSize ? evaluations.get(i) : null;

            boolean hasAnswer = q.userAnswer() != null && !q.userAnswer().isBlank();
            int score = hasAnswer && eval != null ? eval.score() : 0;
            String feedback = eval != null && eval.feedback() != null
                    ? eval.feedback() : "该题未成功生成评估反馈。";
            String refAnswer = eval != null && eval.referenceAnswer() != null
                    ? eval.referenceAnswer() : "";
            List<String> keyPoints = eval != null && eval.keyPoints() != null
                    ? eval.keyPoints() : List.of();

            questionDetails.add(new QuestionEvaluation(
                    q.questionIndex(), q.question(), q.category(), q.userAnswer(), score, feedback
            ));
            referenceAnswers.add(new ReferenceAnswer(
                    q.questionIndex(), q.question(), refAnswer, keyPoints
            ));
            categoryScoresMap.computeIfAbsent(q.category(), k -> new ArrayList<>()).add(score);
        }

        List<CategoryScore> categoryScores = categoryScoresMap.entrySet().stream()
                .map(e -> new CategoryScore(
                        e.getKey(),
                        (int) e.getValue().stream().mapToInt(Integer::intValue).average().orElse(0),
                        e.getValue().size()
                ))
                .collect(Collectors.toList());

        int overallScore = answeredCount == 0 ? 0
                : (int) questionDetails.stream().mapToInt(QuestionEvaluation::score).average().orElse(0);

        return new EvaluationReport(
                sessionId, qaRecords.size(), overallScore, categoryScores, questionDetails,
                overallFeedback,
                strengths != null ? strengths : List.of(),
                improvements != null ? improvements : List.of(),
                referenceAnswers
        );
    }

    /**
     * 构造分类评估摘要文本（用于 LLM 二次汇总的输入）。
     * <p>
     * 格式示例：
     * <pre>
     * - 项目经验: 平均分 82, 题数 4
     * - 技术能力: 平均分 75, 题数 3
     * - 沟通表达: 平均分 68, 题数 2
     * </pre>
     *
     * @param qaRecords   原始问答记录
     * @param evaluations 每道题的评估结果列表
     * @return 分类摘要文本
     */
    private String buildCategorySummary(List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations) {
        Map<String, List<Integer>> categoryScores = new HashMap<>();
        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evaluations.size() ? evaluations.get(i) : null;
            int score = 0;
            if (eval != null && q.userAnswer() != null && !q.userAnswer().isBlank()) {
                score = eval.score();
            }
            categoryScores.computeIfAbsent(q.category(), k -> new ArrayList<>()).add(score);
        }
        return categoryScores.entrySet().stream()
                .map(entry -> {
                    int avg = (int) entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(0);
                    return String.format("- %s: 平均分 %d, 题数 %d", entry.getKey(), avg, entry.getValue().size());
                })
                .sorted()
                .collect(Collectors.joining("\n"));
    }

    /**
     * 构造题目亮点摘要文本（用于 LLM 二次汇总）。
     * <p>对每题生成一行摘要：题号 | 问题（截断 50 字） | 分数 | 反馈（截断 80 字）。</p>
     * <p>截断策略：优先保留开头（通常包含最重要的信息）。</p>
     * 上限 20 条，防止 LLM 输入过长。
     *
     * @param qaRecords   原始问答记录
     * @param evaluations 逐题评估结果
     * @return 题目亮点摘要文本
     */
    private String buildQuestionHighlights(List<QaRecord> qaRecords, List<QuestionEvalDTO> evaluations) {
        List<String> highlights = new ArrayList<>();
        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            QuestionEvalDTO eval = i < evaluations.size() ? evaluations.get(i) : null;
            int score = eval != null ? eval.score() : 0;
            String feedback = eval != null && eval.feedback() != null ? eval.feedback() : "";
            String shortQ = q.question().length() > 50 ? q.question().substring(0, 50) + "..." : q.question();
            String shortF = feedback.length() > 80 ? feedback.substring(0, 80) + "..." : feedback;
            highlights.add(String.format("- Q%d | %s | 分数:%d | 反馈:%s", q.questionIndex() + 1, shortQ, score, shortF));
        }
        return highlights.stream().limit(20).collect(Collectors.joining("\n"));
    }
}

