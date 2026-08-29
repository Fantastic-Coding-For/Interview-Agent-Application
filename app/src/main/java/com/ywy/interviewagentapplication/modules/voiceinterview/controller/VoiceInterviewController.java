package com.ywy.interviewagentapplication.modules.voiceinterview.controller;

import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.common.model.AsyncTaskStatus;
import com.ywy.interviewagentapplication.common.result.Result;
import com.ywy.interviewagentapplication.modules.voiceinterview.dto.CreateSessionRequest;
import com.ywy.interviewagentapplication.modules.voiceinterview.dto.SessionMetaDTO;
import com.ywy.interviewagentapplication.modules.voiceinterview.dto.SessionResponseDTO;
import com.ywy.interviewagentapplication.modules.voiceinterview.dto.VoiceEvaluationDetailDTO;
import com.ywy.interviewagentapplication.modules.voiceinterview.dto.VoiceEvaluationStatusDTO;
import com.ywy.interviewagentapplication.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import com.ywy.interviewagentapplication.modules.voiceinterview.dto.VoiceInterviewMessageDTO;
import com.ywy.interviewagentapplication.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import com.ywy.interviewagentapplication.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import com.ywy.interviewagentapplication.modules.voiceinterview.service.VoiceInterviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 语音面试控制器
 * </p>
 *
 * <h3>「REST 管理 + WebSocket 通话」的双通道架构</h3>
 * 本控制器只管理会话的<b>元数据与生命周期</b>（创建/暂停/恢复/结束/评估），
 * 实时音视频数据流走 WebSocket（/ws/voice-interview/{sessionId}）——
 * REST 通道负责「什么时候面、面完了没」，WS 通道负责「面试过程本身」。
 *
 * <h3>评估的「提交-轮询」协议</h3>
 * <ul>
 *   <li><b>POST /evaluation</b>：触发异步评估（投递 Redis Stream 后立即返回）</li>
 *   <li><b>GET /evaluation</b>：轮询状态——COMPLETED 时附带完整评估报告</li>
 * </ul>
 * 前端轮询同一接口即可在「评估中」与「评估完成」之间自然过渡，
 * 无需两个不同的响应模型。
 */
@RestController
@RequestMapping("/api/voice-interview")
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewController {

    private final VoiceInterviewService voiceInterviewService;
    private final VoiceInterviewEvaluationService evaluationService;
    private final VoiceEvaluateStreamProducer voiceEvaluateStreamProducer;

    /**
     * 创建会话。@Valid 触发生成请求的字段校验（如 plannedDuration 范围），
     * 校验失败由全局异常处理器统一转错误响应。
     */
    @PostMapping("/sessions")
    public Result<SessionResponseDTO> createSession(@Valid @RequestBody CreateSessionRequest request) {
        log.info("Creating voice interview session for role: {}", request.getRoleType());
        SessionResponseDTO session = voiceInterviewService.createSession(request);
        return Result.success(session);
    }

    /**
     * 查询会话详情。未找到返回错误而非 null——详情页直接展示错误提示比静默空白更能引导用户。
     */
    @GetMapping("/sessions/{sessionId}")
    public Result<SessionResponseDTO> getSession(@PathVariable Long sessionId) {
        log.info("Getting session details for: {}", sessionId);
        SessionResponseDTO session = voiceInterviewService.getSessionDTO(sessionId);
        if (session == null) {
            return Result.error("Session not found: " + sessionId);
        }
        return Result.success(session);
    }

    /**
     * 结束会话：置 COMPLETED、算实际时长，并在事务提交后投递异步评估任务
     * （见 VoiceInterviewService.sendEvaluateTaskAfterCommit 的 afterCommit 设计）。
     */
    @PostMapping("/sessions/{sessionId}/end")
    public Result<Void> endSession(@PathVariable Long sessionId) {
        log.info("Ending session: {}", sessionId);
        voiceInterviewService.endSession(sessionId.toString());
        return Result.success();
    }

    /**
     * 暂停会话。reason 是开放 Map（前端可传 "user_initiated"/"timeout"），
     * 默认 user_initiated——宽松入参，暂停原因只是审计信息不影响状态机。
     */
    @PutMapping("/sessions/{sessionId}/pause")
    public Result<Void> pauseSession(
            @PathVariable Long sessionId,
            @RequestBody Map<String, String> request
    ) {
        log.info("Pausing session: {}", sessionId);
        String reason = request.getOrDefault("reason", "user_initiated");
        voiceInterviewService.pauseSession(sessionId.toString(), reason);
        return Result.success();
    }

    /**
     * 恢复会话：返回新的 SessionResponseDTO（含 WebSocket URL），
     * 前端重新建连继续面试。
     */
    @PutMapping("/sessions/{sessionId}/resume")
    public Result<SessionResponseDTO> resumeSession(@PathVariable Long sessionId) {
        log.info("Resuming session: {}", sessionId);
        SessionResponseDTO session = voiceInterviewService.resumeSession(sessionId.toString());
        return Result.success(session);
    }

    /**
     * 筛选会话列表：userId/status 均可选。status 非法值会在 Service 层
     * valueOf 时抛 IllegalArgumentException，由全局异常处理兜底。
     */
    @GetMapping("/sessions")
    public Result<List<SessionMetaDTO>> getAllSessions(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String status
    ) {
        log.info("Getting sessions for user: {}, status: {}", userId, status);
        List<SessionMetaDTO> sessions = voiceInterviewService.getAllSessions(userId, status);
        return Result.success(sessions);
    }

    /**
     * 删除语音面试会话（级联删除消息与评估记录）。
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        log.info("Deleting voice interview session: {}", sessionId);
        voiceInterviewService.deleteSession(sessionId);
        return Result.success();
    }

    /**
     * 获取会话的消息历史（排除 SUMMARY 摘要）。
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<VoiceInterviewMessageDTO>> getMessages(@PathVariable Long sessionId) {
        log.info("Getting messages for session: {}", sessionId);
        List<VoiceInterviewMessageDTO> messages =
                voiceInterviewService.getConversationHistoryDTO(sessionId.toString());
        return Result.success(messages);
    }

    /**
     * 评估状态轮询接口（提交-轮询协议的读端）。
     *
     * <h3>响应组装策略</h3>
     * 始终返回状态三件套（status/error/updatedAt）；仅当 COMPLETED 时
     * 才附带完整评估详情——避免「评估中」阶段的多余查询，
     * 也隐含告诉前端「evaluation 非空即可停止轮询」。
     * evaluateStatus 为 null（会话未结束未触发评估）时如实返回 null，
     * 前端展示「未评估」。
     */
    @GetMapping("/sessions/{sessionId}/evaluation")
    public Result<VoiceEvaluationStatusDTO> getEvaluation(@PathVariable Long sessionId) {
        log.info("Getting evaluation status for session: {}", sessionId);

        VoiceInterviewSessionEntity session = voiceInterviewService.getSession(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在: " + sessionId);
        }

        AsyncTaskStatus status = session.getEvaluateStatus();
        VoiceEvaluationStatusDTO.VoiceEvaluationStatusDTOBuilder builder = VoiceEvaluationStatusDTO.builder()
                .evaluateStatus(status != null ? status.name() : null)
                .evaluateError(session.getEvaluateError())
                .evaluateStatusUpdatedAt(session.getUpdatedAt());

        if (status == AsyncTaskStatus.COMPLETED) {
            VoiceEvaluationDetailDTO evaluation = evaluationService.getEvaluation(sessionId);
            builder.evaluation(evaluation);
        }

        return Result.success(builder.build());
    }

    /**
     * 触发异步评估（提交-轮询协议的写端）。
     *
     * <h3>评估存在时的三种状态分支</h3>
     * <ul>
     *   <li><b>COMPLETED</b>：直接返回缓存结果——重复触发不重新评估，
     *       既省 LLM 成本又保证幂等（前端连点不会刷出多份评估）</li>
     *   <li><b>PROCESSING</b>：返回当前状态让前端继续轮询，<b>不重复投递</b>——
     *       正在执行的 worker 继续跑，重复投递只会造成并发评估竞态</li>
     *   <li><b>其他（PENDING/FAILED/null）</b>：POST 视为显式重试，
     *       重新投递任务（FAILED 后的手动恢复入口）</li>
     * </ul>
     */
    @PostMapping("/sessions/{sessionId}/evaluation")
    public Result<VoiceEvaluationStatusDTO> generateEvaluation(@PathVariable Long sessionId) {
        log.info("Triggering async evaluation for session: {}", sessionId);

        VoiceInterviewSessionEntity session = voiceInterviewService.getSession(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND, "会话不存在: " + sessionId);
        }

        // If already completed, return cached result
        if (session.getEvaluateStatus() == AsyncTaskStatus.COMPLETED) {
            VoiceEvaluationDetailDTO evaluation = evaluationService.getEvaluation(sessionId);
            return Result.success(VoiceEvaluationStatusDTO.builder()
                    .evaluateStatus(AsyncTaskStatus.COMPLETED.name())
                    .evaluateStatusUpdatedAt(session.getUpdatedAt())
                    .evaluation(evaluation)
                    .build());
        }

        // A POST is an explicit retry. Requeue PENDING tasks instead of returning
        // the same stuck state; PROCESSING tasks keep their current worker.
        if (session.getEvaluateStatus() == AsyncTaskStatus.PROCESSING) {
            return Result.success(VoiceEvaluationStatusDTO.builder()
                    .evaluateStatus(session.getEvaluateStatus().name())
                    .evaluateStatusUpdatedAt(session.getUpdatedAt())
                    .build());
        }

        // Trigger new async evaluation via service
        voiceInterviewService.triggerEvaluation(sessionId);

        return Result.success(VoiceEvaluationStatusDTO.builder()
                .evaluateStatus(AsyncTaskStatus.PENDING.name())
                .evaluateStatusUpdatedAt(LocalDateTime.now())
                .build());
    }
}

