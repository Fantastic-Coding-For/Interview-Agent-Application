package com.ywy.interviewagentapplication.modules.interview;

import com.ywy.interviewagentapplication.common.annotation.RateLimit;
import com.ywy.interviewagentapplication.common.result.Result;
import com.ywy.interviewagentapplication.modules.interview.model.CreateInterviewRequest;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewDetailDTO;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewReportDTO;
import com.ywy.interviewagentapplication.modules.interview.model.InterviewSessionDTO;
import com.ywy.interviewagentapplication.modules.interview.model.SessionListItemDTO;
import com.ywy.interviewagentapplication.modules.interview.model.SubmitAnswerRequest;
import com.ywy.interviewagentapplication.modules.interview.model.SubmitAnswerResponse;
import com.ywy.interviewagentapplication.modules.interview.service.InterviewHistoryService;
import com.ywy.interviewagentapplication.modules.interview.service.InterviewPersistenceService;
import com.ywy.interviewagentapplication.modules.interview.service.InterviewSessionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 模拟面试控制器。
 *
 * <h3>限流策略</h3>
 * 仅对创建会话（{@code createSession}）和提交答案（{@code submitAnswer}）
 * 两个核心接口做了限流：
 * <ul>
 *   <li>创建会话：全局限流 5次/秒 + 每IP限流 5次/秒——防止资源滥用</li>
 *   <li>提交答案：全局限流 10次/秒——正常答题节奏不会触发</li>
 * </ul>
 * 其他查询接口不限流——它们只读 Redis/DB，不会调用 LLM。
 *
 * <h3>依赖注入</h3>
 * 三个 Service 各司其职：
 * <ul>
 *   <li>{@code InterviewSessionService}：会话生命周期（创建、答题、暂存、交卷、报告生成）</li>
 *   <li>{@code InterviewHistoryService}：历史查询与详情、PDF 导出</li>
 *   <li>{@code InterviewPersistenceService}：直接数据库操作（列表查询、删除）</li>
 * </ul>
 *
 * @see InterviewSessionService 核心会话管理
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "模拟面试", description = "面试会话创建、问答交互与报告生成")
public class InterviewController {

    private final InterviewSessionService sessionService;
    private final InterviewHistoryService historyService;
    private final InterviewPersistenceService persistenceService;

    /**
     * 列出当前用户的所有面试会话（用于面试记录页）。
     * <p>
     * 注意：当前实现是查询全部记录。实际生产环境需要根据当前登录用户过滤
     * （通过 SecurityContext 或请求头获取 userId）。
     * <p>
     * 使用 {@code SessionListItemDTO.from()} 静态工厂方法而非 MapStruct Mapper，
     * 原因是 SessionListItemDTO 仅需要 Entity 的部分字段且结构固定，
     * 引入 Mapper 反而增加了不必要的抽象层。
     *
     * @return 面试会话摘要列表
     */
    @GetMapping("/api/interview/sessions")
    public Result<List<SessionListItemDTO>> listSessions() {
        List<SessionListItemDTO> items = persistenceService.findAll().stream()
                .map(SessionListItemDTO::from)
                .toList();
        return Result.success(items);
    }

    /**
     * 创建新的 面试会话。
     * <p>
     * <b>限流</b>：全局 + IP 双重限流（AND 关系），防止同一用户大量创建空会话。
     * <p>
     * <b>幂等性</b>：如果请求中携带 requestId，创建操作是幂等的——
     * 重复提交相同 requestId 返回同一个会话，不生成新会话。
     * 实现细节见 {@link InterviewSessionService#createSession}。
     * <p>
     * <b>耗时警告</b>：此接口包含 LLM 出题调用（生成面试问题），
     * 可能需要 3-10 秒。前端应显示加载状态，不应设置过短的超时时间。
     *
     * @param request 创建请求（包含 Skill、难度、题目数、简历等）
     * @return 新创建的会话（含已生成的问题列表）
     */
    @PostMapping("/api/interview/sessions")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<InterviewSessionDTO> createSession(@RequestBody CreateInterviewRequest request) {
        log.info("创建面试会话，题目数量: {}", request.questionCount());
        InterviewSessionDTO session = sessionService.createSession(request);
        return Result.success(session);
    }

    /**
     * 获取会话信息
     */
    @GetMapping("/api/interview/sessions/{sessionId}")
    public Result<InterviewSessionDTO> getSession(@PathVariable String sessionId) {
        InterviewSessionDTO session = sessionService.getSession(sessionId);
        return Result.success(session);
    }

    /**
     * 获取当前待答问题
     */
    @GetMapping("/api/interview/sessions/{sessionId}/question")
    public Result<Map<String, Object>> getCurrentQuestion(@PathVariable String sessionId) {
        return Result.success(sessionService.getCurrentQuestionResponse(sessionId));
    }

    /**
     * 提交答案（并自动进入下一道题）。
     * <p>
     * <b>限流</b>：全局限流 10次/秒。正常用户 30 秒/题，不会触发。
     * 防止的是脚本化批量提交（刷分数/灌答案）。
     * <p>
     * 如果提交的是最后一道题，服务端会自动触发异步评估任务。
     *
     * @param sessionId 会话ID
     * @param body      包含 questionIndex 和 answer 的 Map
     * @return 提交结果（是否有下一道题、下一道题内容、进度）
     */
    @PostMapping("/api/interview/sessions/{sessionId}/answers")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<SubmitAnswerResponse> submitAnswer(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {
        Integer questionIndex = (Integer) body.get("questionIndex");
        String answer = (String) body.get("answer");
        log.info("提交答案: 会话{}, 问题{}", sessionId, questionIndex);
        SubmitAnswerRequest request = new SubmitAnswerRequest(sessionId, questionIndex, answer);
        SubmitAnswerResponse response = sessionService.submitAnswer(request);
        return Result.success(response);
    }

    /**
     * 同步评估面试，并获取评估报告（若已存在报告，更新而非覆盖）。
     * <p>
     * <b>注意</b>：LLM 评估可能需要 30-60 秒，建议前端实现轮询。
     *
     * @param sessionId 会话ID
     * @return 包含逐题评分和总体评价的评估报告
     */
    @GetMapping("/api/interview/sessions/{sessionId}/report")
    public Result<InterviewReportDTO> getReport(@PathVariable String sessionId) {
        log.info("生成面试报告: {}", sessionId);
        InterviewReportDTO report = sessionService.generateReport(sessionId);
        return Result.success(report);
    }

    /**
     * 按简历 ID 查找未完成的面试会话，没找到则抛出异常
     * GET /api/interview/sessions/unfinished/{resumeId}
     */
    @GetMapping("/api/interview/sessions/unfinished/{resumeId}")
    public Result<InterviewSessionDTO> findUnfinishedSession(@PathVariable Long resumeId) {
        return Result.success(sessionService.findUnfinishedSessionOrThrow(resumeId));
    }

    /**
     * 暂存当前答案（不进入下一道题）。
     * <p>
     * 与 {@link #submitAnswer} 的关键区别：暂存后 currentIndex 不变，
     * 用户可以继续编辑当前题的答案。适用于"写了但还没想好，先保存草稿"的场景。
     * <p>
     * 使用 PUT 方法而非 POST，语义上是"更新当前答案"而非"提交新答案"。
     *
     * @param sessionId 会话ID
     * @param body      包含 questionIndex 和 answer 的 Map
     */
    @PutMapping("/api/interview/sessions/{sessionId}/answers")
    public Result<Void> saveAnswer(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {
        Integer questionIndex = (Integer) body.get("questionIndex");
        String answer = (String) body.get("answer");
        log.info("暂存答案: 会话{}, 问题{}", sessionId, questionIndex);
        SubmitAnswerRequest request = new SubmitAnswerRequest(sessionId, questionIndex, answer);
        sessionService.saveAnswer(request);
        return Result.success(null);
    }

    /**
     * 提前完成面试（在回答完所有问题之前主动结束面试），并触发异步评估任务。
     * <p>
     * 使用 POST 方法，这是一个"动作"而非资源更新（RPC-style endpoint）。
     * <p>
     * 如果面试已经完成或已评估，会返回错误（不允许重复交卷）。
     *
     * @param sessionId 会话ID
     */
    @PostMapping("/api/interview/sessions/{sessionId}/complete")
    public Result<Void> completeInterview(@PathVariable String sessionId) {
        log.info("提前交卷: {}", sessionId);
        sessionService.completeInterview(sessionId);
        return Result.success(null);
    }

    /**
     * 获取面试会话详情
     * GET /api/interview/sessions/{sessionId}/details
     */
    @GetMapping("/api/interview/sessions/{sessionId}/details")
    public Result<InterviewDetailDTO> getInterviewDetail(@PathVariable String sessionId) {
        InterviewDetailDTO detail = historyService.getInterviewDetail(sessionId);
        return Result.success(detail);
    }

    /**
     * 导出面试报告为PDF
     */
    @GetMapping("/api/interview/sessions/{sessionId}/export")
    public ResponseEntity<byte[]> exportInterviewPdf(@PathVariable String sessionId) {
        try {
            byte[] pdfBytes = historyService.exportInterviewPdf(sessionId);
            String filename = URLEncoder.encode("模拟面试报告_" + sessionId + ".pdf",
                    StandardCharsets.UTF_8);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdfBytes);
        } catch (Exception e) {
            log.error("导出PDF失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 删除面试会话
     */
    @DeleteMapping("/api/interview/sessions/{sessionId}")
    public Result<Void> deleteInterview(@PathVariable String sessionId) {
        log.info("删除面试会话: {}", sessionId);
        persistenceService.deleteSessionBySessionId(sessionId);
        return Result.success(null);
    }
}

