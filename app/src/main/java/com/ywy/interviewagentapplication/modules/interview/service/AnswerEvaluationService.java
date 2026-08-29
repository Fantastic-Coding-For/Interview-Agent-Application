package com.ywy.interviewagentapplication.modules.interview.service;

import com.ywy.interviewagentapplication.common.evaluation.EvaluationReport;
import com.ywy.interviewagentapplication.common.evaluation.QaRecord;
import com.ywy.interviewagentapplication.common.evaluation.UnifiedEvaluationService;
import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewQuestionDTO;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewReportDTO;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewReportDTO.CategoryScore;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewReportDTO.QuestionEvaluation;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewReportDTO.ReferenceAnswer;
import com.ywy.interviewagentapplication.modules.interview.skill.InterviewSkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文字面试答案评估服务。
 *
 * <h3>架构角色</h3>
 * 本 Service 不包含评估逻辑本身，评估逻辑在 {@link UnifiedEvaluationService} 中。
 * 本类的职责是：
 * <ol>
 *   <li><b>类型适配</b>：将面试模块的 {@link InterviewQuestionDTO} 转为通用评估层的
 *       {@link QaRecord}，使具体评估逻辑无需感知面试模块的数据结构</li>
 *   <li><b>参考上下文组装</b>：从题目中提取参考答案/评分要点/评分规则，
 *       作为评估的参考基线注入 LLM 提示词</li>
 *   <li><b>结果转换</b>：调用统一评估服务，将返回的通用层 {@link EvaluationReport} 转回文字面试模块的
 *       {@link InterviewReportDTO}</li>
 * </ol>
 * <h3>为什么需要适配层？</h3>
 * {@link UnifiedEvaluationService} 被设计为文字面试和语音面试共用。
 * 两种面试的问题数据结构（文字面试用 InterviewQuestionDTO，
 * 语音面试用 VoiceInterviewMessageEntity）和前端需要的评估报告结构均不同，但评估逻辑相同。
 * 适配层将差异隔离在各模块内部，通用评估层只与 QaRecord 和 EvaluationReport 打交道。
 * <h3>参考上下文的两个来源</h3>
 * 参考上下文按优先级尝试：
 * <ol>
 *   <li><b>题目自带</b>（buildQuestionReferenceContext）：题目从题库生成时
 *       携带的参考信息（参考答案、评分要点、评分规则）——最精准</li>
 *   <li><b>Skill 参考基线</b>（skillService.buildEvaluationReferenceSectionSafe）：
 *       从 classpath 加载的 Skill 参考文件——覆盖面更广</li>
 * </ol>
 * 题目自带参考优先，因为它与具体题目精确对应。Skill 基线则作为兜底。
 *
 * @see UnifiedEvaluationService 通用评估引擎
 * @see InterviewSkillService Skill 参考基线提供者
 */
@Service
public class AnswerEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(AnswerEvaluationService.class);

    private final UnifiedEvaluationService unifiedEvaluationService;
    private final InterviewPersistenceService persistenceService;
    private final InterviewSkillService skillService;

    public AnswerEvaluationService(UnifiedEvaluationService unifiedEvaluationService,
                                   InterviewPersistenceService persistenceService,
                                   InterviewSkillService skillService) {
        this.unifiedEvaluationService = unifiedEvaluationService;
        this.persistenceService = persistenceService;
        this.skillService = skillService;
    }

    /**
     * 完整评估面试并生成报告。
     *
     * <h4>评估流程</h4>
     * <ol>
     *   <li>将 InterviewQuestionDTO 列表转为 QaRecord 列表（类型适配）</li>
     *   <li>组装参考上下文（题目自带 + Skill 基线）</li>
     *   <li>调用通用评估服务</li>
     *   <li>将评估报告转为文字面试专用 DTO</li>
     *   <li>补充/纠正参考答案（优先使用题目自带的 referenceAnswer）</li>
     * </ol>
     *
     * <h4>为什么在第5步"纠正"参考答案？</h4>
     * UnifiedEvaluationService 的 LLM 会为每道题生成参考答案，但题目也可能自带
     * 预定义的参考答案（从题库或知识库中获取）。预定义的参考答案质量更稳定
     * （由领域专家审核过），应优先于 LLM 临时生成的。
     *
     * @param chatClient LLM 客户端
     * @param sessionId  会话ID
     * @param resumeText 简历文本
     * @param questions  面试问题列表（含用户答案）
     * @return 面试评估报告
     */
    public InterviewReportDTO evaluateInterview(ChatClient chatClient, String sessionId, String resumeText,
                                                List<InterviewQuestionDTO> questions) {
        log.info("开始评估面试: {}, 共{}题", sessionId, questions.size());

        try {
            // 转为通用问答记录
            List<QaRecord> qaRecords = questions.stream()
                    .map(q -> new QaRecord(q.questionIndex(), q.question(), q.category(), q.userAnswer()))
                    .toList();

            String referenceContext = buildQuestionReferenceContext(questions);
            if (referenceContext.isBlank()) {
                referenceContext = skillService.buildEvaluationReferenceSectionSafe(
                        persistenceService.findBySessionId(sessionId)
                                .map(s -> s.getSkillId())
                                .orElse(null)
                );
            }

            // 调用通用评估服务
            EvaluationReport report = unifiedEvaluationService.evaluate(
                    chatClient, sessionId, qaRecords, resumeText, referenceContext
            );

            // 转为文字面试专用 DTO
            return withQuestionReferences(toInterviewReportDTO(report), questions);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("面试评估失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED,
                    "面试评估失败：" + e.getMessage());
        }
    }

    /**
     * 将通用 EvaluationReport 转换为面试模块的 InterviewReportDTO。
     * <p>
     * 两个结构体字段高度相似（都包含 sessionId、overallScore、categoryScores 等），
     * 但必须做转换。因为各层不应直接依赖另一层的数据结构。
     * 使用 stream().map() 逐字段转换而非 MapStruct 的原因：
     * 字段映射极其简单（1:1 + 全量），引入 Mapper 不增加价值。
     */
    private InterviewReportDTO toInterviewReportDTO(EvaluationReport report) {
        return new InterviewReportDTO(
                report.sessionId(),
                report.totalQuestions(),
                report.overallScore(),
                report.categoryScores().stream()
                        .map(cs -> new CategoryScore(cs.category(), cs.score(), cs.questionCount()))
                        .toList(),
                report.questionDetails().stream()
                        .map(qe -> new QuestionEvaluation(qe.questionIndex(), qe.question(), qe.category(),
                                qe.userAnswer(), qe.score(), qe.feedback()))
                        .toList(),
                report.overallFeedback(),
                report.strengths(),
                report.improvements(),
                report.referenceAnswers().stream()
                        .map(ra -> new ReferenceAnswer(ra.questionIndex(), ra.question(),
                                ra.referenceAnswer(), ra.keyPoints()))
                        .toList()
        );
    }

    /**
     * 用题目自带的参考答案 <b>替换/补充</b> LLM 生成的参考答案。
     * <p>
     * <b>替换策略</b>：如果题目自带非空的 referenceAnswer，优先使用它而非 LLM 生成的。
     * 理由：题目自带的参考答案通常是人工审核过的（更可靠），
     * LLM 生成的参考答案虽然质量高但可能有事实性错误。
     */
    private InterviewReportDTO withQuestionReferences(InterviewReportDTO report,
                                                      List<InterviewQuestionDTO> questions) {
        List<InterviewReportDTO.ReferenceAnswer> references = report.referenceAnswers().stream()
                .map(reference -> {
                    InterviewQuestionDTO question = questions.stream()
                            .filter(item -> item.questionIndex() == reference.questionIndex())
                            .findFirst()
                            .orElse(null);
                    if (question == null || question.referenceAnswer() == null
                            || question.referenceAnswer().isBlank()) {
                        return reference;
                    }
                    return new InterviewReportDTO.ReferenceAnswer(
                            reference.questionIndex(),
                            reference.question(),
                            question.referenceAnswer(),
                            question.keyPoints() != null ? question.keyPoints() : List.of()
                    );
                })
                .toList();
        return new InterviewReportDTO(
                report.sessionId(),
                report.totalQuestions(),
                report.overallScore(),
                report.categoryScores(),
                report.questionDetails(),
                report.overallFeedback(),
                report.strengths(),
                report.improvements(),
                references
        );
    }

    /**
     * 从题目列表中提取参考上下文（参考答案 + 评分要点 + 评分规则）。
     * <p>
     * 仅提取有实际参考信息的题目：如果一道题既没有参考答案、也没有评分要点、
     * 也没有评分规则，则不纳入参考上下文（减少 LLM 输入的噪音）。
     * <p>
     * 输出格式：
     * <pre>
     * 问题1: 请解释 Java 的垃圾回收机制
     * 参考答案: ...（标准答案）
     * 评分要点: 堆内存结构；Minor GC vs Full GC；常见 GC 算法
     * 评分规则: 答对2个要点得一半分，答对3个及以上得满分
     * </pre>
     */
    private String buildQuestionReferenceContext(List<InterviewQuestionDTO> questions) {
        StringBuilder sb = new StringBuilder();
        for (InterviewQuestionDTO question : questions) {
            if (!hasQuestionReference(question)) {
                continue;
            }
            sb.append("问题").append(question.questionIndex() + 1).append(": ")
                    .append(question.question()).append('\n');
            appendIfPresent(sb, "参考答案", question.referenceAnswer());
            if (question.keyPoints() != null && !question.keyPoints().isEmpty()) {
                sb.append("评分要点: ").append(String.join("；", question.keyPoints())).append('\n');
            }
            appendIfPresent(sb, "评分规则", question.scoringRubric());
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 检查题目是否携带任何形式的参考信息 */
    private boolean hasQuestionReference(InterviewQuestionDTO question) {
        return hasText(question.referenceAnswer())
                || hasText(question.scoringRubric())
                || (question.keyPoints() != null && !question.keyPoints().isEmpty());
    }

    /** 条件追加：值非空时才追加 label: value */
    private void appendIfPresent(StringBuilder sb, String label, String value) {
        if (hasText(value)) {
            sb.append(label).append(": ").append(value.trim()).append('\n');
        }
    }

    /** 字符串非空判断，非空且非 null 返回 true */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

