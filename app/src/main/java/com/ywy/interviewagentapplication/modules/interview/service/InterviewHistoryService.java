package com.ywy.interviewagentapplication.modules.interview.service;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.infrastructure.export.PdfExportService;
import com.ywy.interviewagentapplication.infrastructure.mapper.InterviewMapper;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewAnswerEntity;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewDetailDTO;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewQuestionDTO;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewSessionEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 面试历史服务：面试详情查询与报告导出。
 *
 * <h3>职责范围</h3>
 * 本 Service 负责面试<b>完成后</b>的操作（查看详情、导出 PDF），
 * 与 InterviewSessionService 的分工：
 * <ul>
 *   <li>InterviewSessionService → 面试进行中的操作（创建、答题、提交、交卷）</li>
 *   <li>本类 → 面试完成后的操作（查看历史、导出报告）</li>
 * </ul>
 * 这种"进行中 vs 已完成"的职责分离避免了单个 Service 过于庞大。
 *
 * <h3>getInterviewDetail 的数据聚合</h3>
 * 详情页需要展示的信息来自多个数据源：
 * <ol>
 *   <li>Session 实体 → 基本元数据（状态、时间等）</li>
 *   <li>questionsJson → 题目列表（JSON → List）</li>
 *   <li>strengths/improvements/referenceAnswers JSON → 文本列表</li>
 *   <li>Answer 实体列表 → 答题记录</li>
 * </ol>
 * 本方法负责协调这些数据源的加载和组装，最终通过 InterviewMapper
 * 聚合为 InterviewDetailDTO。
 *
 * @see InterviewSessionService 会话管理（进行中操作）
 * @see InterviewMapper DTO 组装器
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewHistoryService {

    private final InterviewPersistenceService interviewPersistenceService;
    private final PdfExportService pdfExportService;
    private final ObjectMapper objectMapper;
    private final InterviewMapper interviewMapper;

    /**
     * 获取面试会话详情（含完整的问答记录和评估数据）。
     *
     * <h4>实现流程</h4>
     * <ol>
     *   <li>查找会话实体（不存在 → 抛异常）</li>
     *   <li>解析所有 JSON 字段（questionsJson、strengthsJson 等）</li>
     *   <li>构建答案详情列表：<b>包含所有题目</b>（未回答的也展示）</li>
     *   <li>使用 InterviewMapper 聚合所有数据为 InterviewDetailDTO</li>
     * </ol>
     *
     * <h4>为什么未回答的题目也要展示？</h4>
     * 详情页的目标是完整展示面试全貌。如果候选人跳过了某道题，
     * 在详情中展示"第 3 题：未回答"比完全不显示更能反映真实情况。
     * 这在提前交卷场景中尤为重要：面试官需要知道哪些题被跳过了。
     *
     * @param sessionId 会话ID
     * @return 包含完整数据的详情 DTO
     */
    @Transactional(readOnly = true)
    public InterviewDetailDTO getInterviewDetail(String sessionId) {
        Optional<InterviewSessionEntity> sessionOpt = interviewPersistenceService.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        InterviewSessionEntity session = sessionOpt.get();

        // 解析JSON字段
        List<Object> questions = parseJson(session.getQuestionsJson(), new TypeReference<>() {});
        List<String> strengths = parseJson(session.getStrengthsJson(), new TypeReference<>() {});
        List<String> improvements = parseJson(session.getImprovementsJson(), new TypeReference<>() {});
        List<Object> referenceAnswers = parseJson(session.getReferenceAnswersJson(), new TypeReference<>() {});

        // 解析所有题目（用于构建完整的答案列表）
        List<InterviewQuestionDTO> allQuestions = parseJson(
                session.getQuestionsJson(),
                new TypeReference<>() {
                }
        );

        // 构建答案详情列表（包含所有题目，未回答的也要显示）
        List<InterviewDetailDTO.AnswerDetailDTO> answerList = buildAnswerDetailList(
                allQuestions,
                session.getAnswers()
        );

        // 使用 MapStruct 组装最终 DTO
        return interviewMapper.toDetailDTO(
                session,
                questions,
                strengths,
                improvements,
                referenceAnswers,
                answerList
        );
    }

    /**
     * 构建答案详情列表：将所有题目与用户的答案记录对齐。
     *
     * <h4>对齐策略</h4>
     * <ol>
     *   <li>将答案实体列表转为 questionIndex → AnswerEntity 的 Map（O(1) 查找）</li>
     *   <li>遍历所有题目，对每道题查找对应的答案记录</li>
     *   <li>有答案记录 → 使用 InterviewMapper 转换</li>
     *   <li>无答案记录 → 构建空答案 DTO（score=0, userAnswer=null）</li>
     * </ol>
     *
     * <h4>重复 questionIndex 的处理</h4>
     * {@code Collectors.toMap} 的 merge 函数取第一个答案（{@code (a1, a2) -> a1}）。
     * 理论上同一会话内不应出现重复的 questionIndex，但如果有（数据异常），
     * 保留第一条而非抛异常：详情页展示优于报错。
     *
     * @param allQuestions 所有题目（含追问）
     * @param answers      用户的答案记录（未回答或提前交卷时，可能少于题目数）
     * @return 与题目一一对应的答案详情列表
     */
    private List<InterviewDetailDTO.AnswerDetailDTO> buildAnswerDetailList(
            List<InterviewQuestionDTO> allQuestions,
            List<InterviewAnswerEntity> answers
    ) {
        if (allQuestions == null || allQuestions.isEmpty()) {
            // 如果没有题目数据，回退到仅显示已回答的题目
            return interviewMapper.toAnswerDetailDTOList(answers, this::extractKeyPoints);
        }

        // 将答案按 questionIndex 索引
        java.util.Map<Integer, InterviewAnswerEntity> answerMap = answers.stream()
                .collect(java.util.stream.Collectors.toMap(
                        InterviewAnswerEntity::getQuestionIndex,
                        a -> a,
                        (a1, a2) -> a1  // 如果有重复，取第一个
                ));

        // 遍历所有题目，构建完整的答案详情列表
        return allQuestions.stream()
                .map(question -> {
                    InterviewAnswerEntity answer = answerMap.get(question.questionIndex());
                    if (answer != null) {
                        // 用户已回答，使用答案数据
                        return interviewMapper.toAnswerDetailDTO(answer, extractKeyPoints(answer));
                    } else {
                        // 用户未回答，构建空答案
                        return new InterviewDetailDTO.AnswerDetailDTO(
                                question.questionIndex(),
                                question.question(),
                                question.category(),
                                null,  // userAnswer
                                question.score() != null ? question.score() : 0,  // score
                                question.feedback(),  // feedback
                                null,  // referenceAnswer
                                null,  // keyPoints
                                null   // answeredAt
                        );
                    }
                })
                .toList();
    }

    /**
     * 从答案实体的 JSON 字段中提取关键要点列表。
     * <p>
     * keyPoints 在数据库中存储为 JSON 字符串（keyPointsJson 字段），
     * 需要通过 ObjectMapper 反序列化。如果 JSON 为 null 或解析失败，返回 null。
     */
    private List<String> extractKeyPoints(InterviewAnswerEntity answer) {
        return parseJson(answer.getKeyPointsJson(), new TypeReference<>() {});
    }

    /**
     * 通用 JSON 解析方法。安全解析，失败返回 null。
     */
    private <T> T parseJson(String json, TypeReference<T> typeRef) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JacksonException e) {
            log.error("解析 JSON 失败", e);
            return null;
        }
    }

    /**
     * 导出面试报告为 PDF 字节数组。
     * <p>
     * 委托给 {@link PdfExportService} 生成 PDF 内容。
     * 会话不存在时抛出 BusinessException（3001）而非返回 null。
     *
     * @param sessionId 会话ID
     * @return PDF 文件字节数组
     * @throws BusinessException 如果会话不存在或 PDF 生成失败
     */
    public byte[] exportInterviewPdf(String sessionId) {
        Optional<InterviewSessionEntity> sessionOpt = interviewPersistenceService.findBySessionId(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
        }

        InterviewSessionEntity session = sessionOpt.get();
        try {
            return pdfExportService.exportInterviewReport(session);
        } catch (Exception e) {
            log.error("导出PDF失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "导出PDF失败: " + e.getMessage());
        }
    }
}

