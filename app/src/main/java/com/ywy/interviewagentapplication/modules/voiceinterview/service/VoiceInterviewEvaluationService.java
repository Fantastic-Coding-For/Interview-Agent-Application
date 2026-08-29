package com.ywy.interviewagentapplication.modules.voiceinterview.service;

import com.ywy.interviewagentapplication.common.ai.LlmProviderRegistry;
import com.ywy.interviewagentapplication.common.evaluation.EvaluationReport;
import com.ywy.interviewagentapplication.common.evaluation.QaRecord;
import com.ywy.interviewagentapplication.common.evaluation.UnifiedEvaluationService;
import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.modules.interview.skill.InterviewSkillService;
import com.ywy.interviewagentapplication.modules.voiceinterview.dto.VoiceEvaluationDetailDTO;
import com.ywy.interviewagentapplication.modules.voiceinterview.dto.VoiceEvaluationDetailDTO.AnswerDetail;
import com.ywy.interviewagentapplication.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import com.ywy.interviewagentapplication.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import com.ywy.interviewagentapplication.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import com.ywy.interviewagentapplication.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import com.ywy.interviewagentapplication.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import com.ywy.interviewagentapplication.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 语音面试评估服务
 * 复用 UnifiedEvaluationService 的分批评估 + 结构化输出 + 降级兜底。
 *
 * <h3>复用 vs 重写的边界</h3>
 * 语音面试与文本面试的<b>评估引擎完全同构</b>（同一套分批评估、结构化输出、
 * 降级兜底），差异仅在「输入对话的来源」：文本面试给原文，语音面试给
 * ASR 转写文本。因此本服务不实现评估算法，只做三件事：
 * <ol>
 *   <li><b>对话重建</b>：把「一问一答」的行消息实体列表流转换为
 *       UnifiedEvaluationService 需要的 QaRecord 问答对列表（buildQaRecords）</li>
 *   <li><b>上下文补充</b>：从技能服务取评估参考标准（referenceContext）</li>
 *   <li><b>结果落库/读取</b>：EvaluationReport → JSON 列持久化；JSON 列 → 详情 DTO</li>
 * </ol>
 *
 * <h3>事务边界设计</h3>
 * {@code generateEvaluation} 本身<b>不在事务中</b>：LLM 评估可能耗时数十秒，
 * 长事务会锁表并耗尽连接池。只有最后的保存（saveEvaluationTransactional /
 * saveEmptyEvaluationTransactional）用独立小事务——「重计算在外、轻持久化在内」。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VoiceInterviewEvaluationService {

    private final UnifiedEvaluationService unifiedEvaluationService;
    private final LlmProviderRegistry llmProviderRegistry;
    private final VoiceInterviewEvaluationRepository evaluationRepository;
    private final VoiceInterviewMessageRepository messageRepository;
    private final VoiceInterviewSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    private final InterviewSkillService skillService;

    /**
     * 生成语音面试评估（由异步消费者调用）
     * LLM 调用在事务外执行，仅 DB 写入在事务内。
     *
     * <h3>无对话记录的特殊处理</h3>
     * 不直接失败，而是落一条「空评估」记录（overallScore=null + 引导性文案）：
     * 消费者会把它当作成功（markCompleted），避免空会话反复重试浪费资源；
     * 前端拿到 COMPLETED 状态 + 引导文案，体验远好于「评估失败，请重试」。
     *
     * @param sessionId 会话ID
     * @throws BusinessException 会话不存在或评估生成失败时抛出（由消费者的失败钩子接管重试）
     */
    public void generateEvaluation(Long sessionId) {
        try {
            log.info("开始生成语音面试评估: sessionId={}", sessionId);

            VoiceInterviewSessionEntity session = getSession(sessionId);
            List<VoiceInterviewMessageEntity> messages = messageRepository
                    .findBySessionIdAndMessageTypeNotOrderBySequenceNumAsc(
                            sessionId, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY);

            if (messages.isEmpty()) {
                log.warn("语音面试会话无对话记录，生成空评估结果: sessionId={}", sessionId);
                saveEmptyEvaluationTransactional(sessionId, session);
                return;
            }

            List<QaRecord> qaRecords = buildQaRecords(messages);

            String provider = session.getLlmProvider();
            ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(provider);

            String sessionIdStr = String.valueOf(sessionId);
            String referenceContext = skillService.buildEvaluationReferenceSectionSafe(session.getSkillId());
            EvaluationReport report = unifiedEvaluationService.evaluate(
                    chatClient, sessionIdStr, qaRecords, null, referenceContext);

            saveEvaluationTransactional(sessionId, session, report);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("生成语音面试评估失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED,
                    "生成评估失败: " + e.getMessage());
        }
    }

    /**
     * 读取评估结果并组装评估详情 DTO。
     */
    public VoiceEvaluationDetailDTO getEvaluation(Long sessionId) {
        VoiceInterviewEvaluationEntity evaluation = evaluationRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_EVALUATION_NOT_FOUND,
                        "评估结果不存在: " + sessionId));

        return buildDetailDTO(evaluation);
    }

    /**
     * 把消息实体列表重建为问答对列表。
     *
     * <h3>为什么需要重建</h3>
     * 消息表每行是「一问一答」的两阶段写入模型，但评估引擎要的是
     * 「每个问题配一个回答」的 QaRecord。重建规则：
     * <ul>
     *   <li>AI 提问先行落库、用户回答后到：用 pendingQuestion 暂存未配对的提问，
     *       回答出现时配对成 QaRecord</li>
     *   <li>只有提问没有回答（如最后一道题没答就结束）：以 answer=null 入列——
     *       评估引擎会按「未作答」计分，这是真实信息，不应丢弃</li>
     *   <li>孤立用户文本（理论上少见）：category 归「综合」，question 留空</li>
     * </ul>
     */
    private List<QaRecord> buildQaRecords(List<VoiceInterviewMessageEntity> messages) {
        List<QaRecord> records = new ArrayList<>();
        int index = 0;
        PendingQuestion pendingQuestion = null;

        for (VoiceInterviewMessageEntity msg : messages) {
            String aiText = VoiceInterviewMessageEntity.trimToNull(msg.getAiGeneratedText());
            String userText = VoiceInterviewMessageEntity.trimToNull(msg.getUserRecognizedText());

            if (pendingQuestion != null && userText != null) {
                records.add(new QaRecord(
                        index,
                        pendingQuestion.question(),
                        pendingQuestion.category(),
                        userText
                ));
                index++;
                pendingQuestion = null;
                if (aiText != null) {
                    pendingQuestion = new PendingQuestion(aiText, inferCategory(aiText));
                }
                continue;
            }

            if (pendingQuestion != null) {
                records.add(new QaRecord(
                        index,
                        pendingQuestion.question(),
                        pendingQuestion.category(),
                        null
                ));
                index++;
                pendingQuestion = null;
            }

            if (aiText != null && userText != null) {
                records.add(new QaRecord(index, aiText, inferCategory(aiText), userText));
                index++;
            } else if (aiText != null) {
                pendingQuestion = new PendingQuestion(aiText, inferCategory(aiText));
            } else if (userText != null) {
                records.add(new QaRecord(index, "", "综合", userText));
                index++;
            }
        }

        if (pendingQuestion != null) {
            records.add(new QaRecord(
                    index,
                    pendingQuestion.question(),
                    pendingQuestion.category(),
                    null
            ));
        }

        return records;
    }

    /**
     * 未配对答案的提问暂存载体（question + 推断的类别）。
     */
    private record PendingQuestion(String question, String category) {}

    /**
     * 按提问文本关键词推断题目类别（供评估报告分组展示）。
     *
     * <p>关键词顺序即优先级：项目类 → 自我介绍类 → HR 类 → 技术类（兜底）。
     * 技术类兜底的原因是技术面试中技术问题占比最高，识别不出的都归入技术。
     */
    private String inferCategory(String aiText) {
        if (aiText == null) return "综合";
        if (aiText.contains("项目") || aiText.contains("实习") || aiText.contains("工作经历")) return "项目深挖";
        if (aiText.contains("自我介绍") || aiText.contains("介绍一下自己")) return "自我介绍";
        if (aiText.contains("职业规划") || aiText.contains("为什么") || aiText.contains("优缺点")) return "HR问题";
        return "技术问题";
    }

    /**
     * 通过独立事务保存评估结果。
     *
     * <p>结构对象（逐题评估、亮点等）序列化为 JSON 字符串存入 TEXT 列
     * （见 VoiceInterviewEvaluationEntity 的 JSON 列设计）。
     * 保存失败转 BusinessException 让消费者走重试——「评估出来了但没存上」
     * 重试一次通常就能成功，不必让用户重新触发。
     */
    @Transactional
    public void saveEvaluationTransactional(Long sessionId, VoiceInterviewSessionEntity session,
                                            EvaluationReport report) {
        try {
            List<EvaluationReport.QuestionEvaluation> questionItems = report.questionDetails();
            List<EvaluationReport.ReferenceAnswer> refAnswerItems = report.referenceAnswers();

            VoiceInterviewEvaluationEntity entity = VoiceInterviewEvaluationEntity.builder()
                    .sessionId(sessionId)
                    .overallScore(report.overallScore())
                    .overallFeedback(report.overallFeedback())
                    .questionEvaluationsJson(objectMapper.writeValueAsString(questionItems))
                    .strengthsJson(objectMapper.writeValueAsString(report.strengths()))
                    .improvementsJson(objectMapper.writeValueAsString(report.improvements()))
                    .referenceAnswersJson(objectMapper.writeValueAsString(refAnswerItems))
                    .interviewerRole(session.getRoleType())
                    .interviewDate(session.getStartTime())
                    .build();

            evaluationRepository.save(entity);
            log.info("评估结果已保存: sessionId={}, score={}", sessionId, entity.getOverallScore());
        } catch (Exception e) {
            log.error("保存评估结果失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED,
                    "保存评估失败: " + e.getMessage());
        }
    }

    /**
     * 通过独立事务保存空评估结果（无对话记录时）。
     *
     * <p>orElseGet 的 UPSERT 语义：若已有评估行则覆盖为空评估。
     * improvements 里的引导文案直接写给用户看：问题在于没有对话，而不是系统故障。
     */
    @Transactional
    public void saveEmptyEvaluationTransactional(Long sessionId, VoiceInterviewSessionEntity session) {
        try {
            VoiceInterviewEvaluationEntity entity = evaluationRepository.findBySessionId(sessionId)
                    .orElseGet(() -> VoiceInterviewEvaluationEntity.builder().sessionId(sessionId).build());

            entity.setOverallScore(null);
            entity.setOverallFeedback("本次语音面试未形成有效对话记录，暂无可评估内容。");
            entity.setQuestionEvaluationsJson("[]");
            entity.setStrengthsJson("[]");
            entity.setImprovementsJson("[\"请先完成至少一轮有效问答后再生成评估。\"]");
            entity.setReferenceAnswersJson("[]");
            entity.setInterviewerRole(session.getRoleType());
            entity.setInterviewDate(session.getStartTime());

            evaluationRepository.save(entity);
            log.info("空评估结果已保存: sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("保存空评估结果失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED,
                    "保存空评估失败: " + e.getMessage());
        }
    }

    /**
     * 实体 → 详情 DTO：反序列化四个 JSON 列，并按 questionIndex 把
     * 逐题评估与参考回答连接起来。
     * <p>
     * toMap 的 (a, b) -> a 合并函数：重复 questionIndex 保留第一个
     * （理论不应出现，防御性去重）。
     */
    private VoiceEvaluationDetailDTO buildDetailDTO(VoiceInterviewEvaluationEntity entity) {
        try {
            List<EvaluationReport.QuestionEvaluation> questionItems = objectMapper.readValue(
                    entity.getQuestionEvaluationsJson(),
                    new TypeReference<List<EvaluationReport.QuestionEvaluation>>() {}
            );

            List<String> strengths = objectMapper.readValue(
                    entity.getStrengthsJson(),
                    new TypeReference<List<String>>() {}
            );

            List<String> improvements = objectMapper.readValue(
                    entity.getImprovementsJson(),
                    new TypeReference<List<String>>() {}
            );

            List<EvaluationReport.ReferenceAnswer> refAnswers = objectMapper.readValue(
                    entity.getReferenceAnswersJson(),
                    new TypeReference<List<EvaluationReport.ReferenceAnswer>>() {}
            );

            Map<Integer, EvaluationReport.ReferenceAnswer> refMap = refAnswers.stream()
                    .collect(Collectors.toMap(
                            EvaluationReport.ReferenceAnswer::questionIndex, r -> r, (a, b) -> a));

            List<AnswerDetail> answers = new ArrayList<>();
            for (EvaluationReport.QuestionEvaluation q : questionItems) {
                EvaluationReport.ReferenceAnswer ref = refMap.get(q.questionIndex());
                answers.add(AnswerDetail.builder()
                        .questionIndex(q.questionIndex())
                        .question(q.question())
                        .category(q.category())
                        .userAnswer(q.userAnswer())
                        .score(q.score())
                        .feedback(q.feedback())
                        .referenceAnswer(ref != null ? ref.referenceAnswer() : null)
                        .keyPoints(ref != null ? ref.keyPoints() : null)
                        .build());
            }

            return VoiceEvaluationDetailDTO.builder()
                    .sessionId(entity.getSessionId())
                    .totalQuestions(answers.size())
                    .overallScore(entity.getOverallScore())
                    .overallFeedback(entity.getOverallFeedback())
                    .strengths(strengths)
                    .improvements(improvements)
                    .answers(answers)
                    .build();

        } catch (Exception e) {
            log.error("构建评估详情失败: sessionId={}", entity.getSessionId(), e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED,
                    "构建评估结果失败: " + e.getMessage());
        }
    }

    /**
     * 获取面试会话实体（不存在抛业务异常）。
     */
    private VoiceInterviewSessionEntity getSession(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND,
                        "语音面试会话不存在: " + sessionId));
    }
}

