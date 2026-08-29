package com.ywy.interviewagentapplication.modules.resume.service;

import com.ywy.interviewagentapplication.common.ai.LlmProviderRegistry;
import com.ywy.interviewagentapplication.common.ai.StructuredOutputInvoker;
import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.modules.interview.model.ResumeAnalysisResponse;
import com.ywy.interviewagentapplication.modules.interview.model.ResumeAnalysisResponse.ScoreDetail;
import com.ywy.interviewagentapplication.modules.interview.model.ResumeAnalysisResponse.Suggestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <h1>简历 AI 评分服务</h1>
 *
 * <p>使用 Spring AI 框架调用 LLM 对简历进行智能评分和改进建议生成。</p>
 *
 * <h2>核心流程</h2>
 * <pre>
 *  简历文本 → 加载 Prompt 模板 → 填充变量 → 附加结构化输出格式指令
 *           → 调用 LLM（ChatClient） → 解析 JSON 响应（BeanOutputConverter）
 *           → 转换为业务对象（ResumeAnalysisResponse）
 * </pre>
 *
 * <h2>Spring AI 关键组件说明</h2>
 *
 * <h3>1. PromptTemplate（提示词模板）</h3>
 * <p>从类路径下的资源文件加载提示词模板。分为两部分：</p>
 * <ul>
 *   <li><b>System Prompt</b>：定义 AI 的角色和行为规范（如"你是一位资深 HR..."）</li>
 *   <li><b>User Prompt</b>：包含具体的简历文本和评分要求，使用 {@code {resumeText}} 占位符</li>
 * </ul>
 * <p>模板文件路径通过 {@link ResumeAnalysisProperties} 配置（如 {@code classpath:prompts/resume-system.txt}）。</p>
 *
 * <h3>2. BeanOutputConverter（结构化输出转换器）</h3>
 * <p>Spring AI 提供的核心组件，解决 LLM 输出<b>非结构化</b>的问题：</p>
 * <ul>
 *   <li>根据 Java Record/Class 的结构自动生成 JSON Schema 指令</li>
 *   <li>将格式指令附加到 System Prompt 末尾（如 "You must respond in JSON format..."）</li>
 *   <li>接收 LLM 的文本响应，自动反序列化为 Java 对象</li>
 * </ul>
 * <p>这样开发者只需定义 Java Record 来描述期望的输出结构，无需手动编写 JSON Schema。</p>
 *
 * <h3>3. StructuredOutputInvoker（结构化输出调用器）</h3>
 * <p>项目自定义的封装类，在 BeanOutputConverter 基础上增加了：</p>
 * <ul>
 *   <li><b>重试机制</b>：LLM 返回格式不符合 JSON Schema 时自动重试</li>
 *   <li><b>统一异常处理</b>：将 Spring AI 的异常转换为项目的 BusinessException</li>
 *   <li><b>日志记录</b>：自动记录调用耗时和结果</li>
 * </ul>
 *
 * <h2>评分维度</h2>
 * <p>AI 对简历从以下 5 个维度评分（每项满分 100）：</p>
 * <ol>
 *   <li><b>内容质量</b>（contentScore）：工作经历描述的丰富度和专业性</li>
 *   <li><b>结构规范</b>（structureScore）：排版、段落划分、信息组织的合理性</li>
 *   <li><b>技能匹配</b>（skillMatchScore）：技能描述的完整度和相关性</li>
 *   <li><b>表达水平</b>（expressionScore）：语言表达的专业性和流畅度</li>
 *   <li><b>项目经验</b>（projectScore）：项目描述的清晰度和成果量化程度</li>
 * </ol>
 *
 * <h2>DTO 分层设计</h2>
 * <p>本类使用了<b>三层数据结构</b>：</p>
 * <pre>
 *  LLM 输出（JSON）
 *    → ResumeAnalysisResponseDTO（AI 响应的直接映射，内部 Record）
 *      → ResumeAnalysisResponse（业务层 DTO，包含 ScoreDetail 和 Suggestion）
 * </pre>
 * <p>中间层 DTO（Record）与 AI 响应结构一一对应，不会暴露给外部。
 * 最终对外返回的是业务层 DTO，可以脱离 AI 响应格式独立演进。</p>
 *
 * @see StructuredOutputInvoker 结构化输出的通用调用器
 * @see LlmProviderRegistry 管理 LLM Provider 实例
 * @see ResumeAnalysisProperties 提示词路径等配置
 */
@Service
public class ResumeGradingService {

    private static final Logger log = LoggerFactory.getLogger(ResumeGradingService.class);

    private final LlmProviderRegistry llmProviderRegistry;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<ResumeAnalysisResponseDTO> outputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;

    /**
     * <h3>AI 响应的顶层 DTO</h3>
     * 对应 LLM 返回的完整 JSON 对象。
     */
    private record ResumeAnalysisResponseDTO(
            int overallScore,
            ScoreDetailDTO scoreDetail,
            String summary,
            List<String> strengths,
            List<SuggestionDTO> suggestions
    ) {}

    /**
     * <h3>评分明细 DTO</h3>
     * 对应 LLM 返回的 scoreDetail 子对象，包含 5 个维度的分数。
     */
    private record ScoreDetailDTO(
            int contentScore,
            int structureScore,
            int skillMatchScore,
            int expressionScore,
            int projectScore
    ) {}

    /**
     * <h3>改进建议 DTO</h3>
     * 对应 LLM 返回的 suggestions 数组中的每个元素。
     */
    private record SuggestionDTO(
            String category,
            String priority,
            String issue,
            String recommendation
    ) {}

    public ResumeGradingService(
            LlmProviderRegistry llmProviderRegistry,
            StructuredOutputInvoker structuredOutputInvoker,
            ResumeAnalysisProperties properties,
            ResourceLoader resourceLoader) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.systemPromptTemplate = new PromptTemplate(
                resourceLoader.getResource(properties.getSystemPromptPath())
                        .getContentAsString(StandardCharsets.UTF_8)
        );
        this.userPromptTemplate = new PromptTemplate(
                resourceLoader.getResource(properties.getUserPromptPath())
                        .getContentAsString(StandardCharsets.UTF_8)
        );
        this.outputConverter = new BeanOutputConverter<>(ResumeAnalysisResponseDTO.class);
    }

    /**
     * <h3>分析简历并返回评分和建议</h3>
     *
     * <p>完整的 AI 分析流程：</p>
     * <ol>
     *   <li><b>渲染 System Prompt</b>：将模板渲染为最终的系统提示词</li>
     *   <li><b>渲染 User Prompt</b>：将简历文本填充到用户提示词模板的 {@code {resumeText}} 占位符</li>
     *   <li><b>附加格式指令</b>：将 BeanOutputConverter 生成的 JSON Schema 格式要求
     *       追加到 System Prompt 末尾，确保 LLM 按照指定的 JSON 格式输出</li>
     *   <li><b>调用 LLM</b>：通过 {@link StructuredOutputInvoker} 发送请求，
     *       自动处理重试和格式校验</li>
     *   <li><b>转换响应</b>：将 AI 返回的 DTO 转换为业务层对象（前端需要）</li>
     * </ol>
     *
     * <p><b>异常处理策略：</b></p>
     * <ul>
     *   <li>内层 try-catch：LLM 调用失败（网络错误、API Key 无效等）→ 抛出
     *       BusinessException 供外层捕获</li>
     *   <li>外层 try-catch：捕获<b>所有异常</b>（包括内层抛出的 BusinessException），
     *       统一返回降级响应（overallScore=0），确保调用方始终能拿到有效对象</li>
     * </ul>
     * <p>这种策略确保调用方（Consumer）不会因为未捕获的异常导致
     * Stream 消息处理中断。<b>本方法永远不会抛出异常</b>。</p>
     *
     * @param resumeText 简历的纯文本内容
     * @return 包含评分、摘要、优势、建议的分析结果（异常时返回 overallScore=0 的降级响应）
     */
    public ResumeAnalysisResponse analyzeResume(String resumeText) {
        log.info("开始分析简历，文本长度: {} 字符", resumeText.length());

        try {
            // 加载系统提示词
            String systemPrompt = systemPromptTemplate.render();

            // 加载用户提示词并填充变量
            Map<String, Object> variables = new HashMap<>();
            variables.put("resumeText", resumeText);
            String userPrompt = userPromptTemplate.render(variables);

            // 添加格式指令到系统提示词
            String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();

            // 调用默认的 AI chat 模型
            ResumeAnalysisResponseDTO dto;
            try {
                ChatClient chatClient = llmProviderRegistry.getDefaultChatClient();
                dto = structuredOutputInvoker.invoke(
                        chatClient,
                        systemPromptWithFormat,
                        userPrompt,
                        outputConverter,
                        ErrorCode.RESUME_ANALYSIS_FAILED,
                        "简历分析失败：",
                        "简历分析",
                        log
                );
                log.debug("AI响应解析成功: overallScore={}", dto.overallScore());
            } catch (Exception e) {
                log.error("简历分析AI调用失败: {}", e.getMessage(), e);
                throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "简历分析失败：" + e.getMessage());
            }

            // 转换为业务对象
            ResumeAnalysisResponse result = convertToResponse(dto, resumeText);
            log.info("简历分析完成，总分: {}", result.overallScore());

            return result;

        } catch (Exception e) {
            log.error("简历分析失败: {}", e.getMessage(), e);
            return createErrorResponse(resumeText, e.getMessage());
        }
    }

    /**
     * <h3>将转换为 DTO 的 AI 输出和简历内容封装为业务 DTO（前端需要）</h3>
     * 这层转换隔离了 AI 响应格式与业务接口：</p>
     * <ul>
     *   <li>AI 响应格式变化（如字段重命名）只需修改 Record 和此方法</li>
     *   <li>业务 DTO 可以独立演进，不受 AI Prompt 格式影响</li>
     *   <li>可以在转换过程中做额外的数据处理（如分数归一化、敏感词过滤等）</li>
     * </ul>
     *
     * @param dto          AI 返回的原始 DTO
     * @param originalText 原始简历文本（存入响应对象供前端展示）
     * @return 业务层分析响应对象
     */
    private ResumeAnalysisResponse convertToResponse(ResumeAnalysisResponseDTO dto, String originalText) {
        ScoreDetail scoreDetail = new ScoreDetail(
                dto.scoreDetail().contentScore(),
                dto.scoreDetail().structureScore(),
                dto.scoreDetail().skillMatchScore(),
                dto.scoreDetail().expressionScore(),
                dto.scoreDetail().projectScore()
        );

        List<Suggestion> suggestions = dto.suggestions().stream()
                .map(s -> new Suggestion(s.category(), s.priority(), s.issue(), s.recommendation()))
                .toList();

        return new ResumeAnalysisResponse(
                dto.overallScore(),
                scoreDetail,
                dto.summary(),
                dto.strengths(),
                suggestions,
                originalText
        );
    }

    /**
     * <h3>创建降级错误响应</h3>
     *
     * <p>当 AI 分析过程中发生未预期的异常时调用。返回一个"安全"的响应对象：</p>
     * <ul>
     *   <li>overallScore = 0（明确表示分析失败）</li>
     *   <li>所有子项分数 = 0</li>
     *   <li>summary 包含错误描述</li>
     *   <li>返回一条系统级别的建议，提示用户重试或检查服务状态</li>
     * </ul>
     *
     * <p>这个降级响应的目的是<b>优雅降级</b>，即使 AI 服务不可用，
     * 系统也不会崩溃，用户可以收到明确的错误提示。</p>
     *
     * @param originalText 原始简历文本
     * @param errorMessage 错误消息
     * @return 降级后的分析结果（所有分数为 0）
     */
    private ResumeAnalysisResponse createErrorResponse(String originalText, String errorMessage) {
        return new ResumeAnalysisResponse(
                0,
                new ScoreDetail(0, 0, 0, 0, 0),
                "分析过程中出现错误: " + errorMessage,
                List.of(),
                List.of(new Suggestion(
                        "系统",
                        "高",
                        "AI分析服务暂时不可用",
                        "请稍后重试，或检查AI服务是否正常运行"
                )),
                originalText
        );
    }
}

