package com.ywy.interviewagentapplication.modules.knowledgebase;

import com.ywy.interviewagentapplication.common.result.Result;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.RagChatDTO.CreateSessionRequest;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.RagChatDTO.SendMessageRequest;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.RagChatDTO.SessionDTO;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.RagChatDTO.SessionDetailDTO;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.RagChatDTO.SessionListItemDTO;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.RagChatDTO.UpdateKnowledgeBasesRequest;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.RagChatDTO.UpdateTitleRequest;
import com.ywy.interviewagentapplication.modules.knowledgebase.service.RagChatSessionService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * RAG 聊天 REST API 控制器：基于知识库的多轮对话。
 *
 * <h3>与 KnowledgeBaseController 的分工</h3>
 * <ul>
 *   <li>KnowledgeBaseController → 知识库<b>管理</b>（上传、分类、向量化）和<b>单轮问答</b></li>
 *   <li>本 Controller → RAG <b>多轮聊天</b>（会话管理、带历史上下文的流式对话）</li>
 * </ul>
 * 区别的本质：单轮问答无状态（每次查询独立），
 * 聊天有状态（会话、历史消息、上下文延续）。
 *
 * <h3>流式消息的生命周期（三步模式）</h3>
 * {@link #sendMessageStream} 体现了流式聊天的标准三步：
 * <ol>
 *   <li><b>prepare</b>：流开始前——同步保存用户消息 + 创建 AI 消息占位（completed=false）</li>
 *   <li><b>stream</b>：流过程中——LLM 逐 token 输出，前端实时渲染</li>
 *   <li><b>complete</b>：流结束后——将完整回答写回占位消息（completed=true）</li>
 * </ol>
 * 占位消息的意义：即使流式中断（网络断开），占位记录也存在，
 * 前端刷新后可以看到"这次回答未完成"的状态，而非消息凭空消失。
 *
 * <h3>SSE 换行符转义的必要性</h3>
 * Server-Sent Events 协议中，每个事件以空行分隔。如果回答内容本身
 * 包含换行符，会被解析器误认为事件边界导致数据截断。
 * 因此将 chunk 中的 \n → \\n、\r → \\r 转义，前端收到后反转义即可。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "RAG 问答", description = "基于知识库的智能问答会话")
public class RagChatController {

    private final RagChatSessionService sessionService;

    /**
     * 创建新会话
     */
    @PostMapping("/api/rag-chat/sessions")
    public Result<SessionDTO> createSession(@Valid @RequestBody CreateSessionRequest request) {
        return Result.success(sessionService.createSession(request));
    }

    /**
     * 获取会话列表（按置顶状态和更新时间排序：置顶的在前，然后按更新时间降序）。
     */
    @GetMapping("/api/rag-chat/sessions")
    public Result<List<SessionListItemDTO>> listSessions() {
        return Result.success(sessionService.listSessions());
    }

    /**
     * 获取会话详情（包含消息历史）
     * GET /api/rag-chat/sessions/{sessionId}
     */
    @GetMapping("/api/rag-chat/sessions/{sessionId}")
    public Result<SessionDetailDTO> getSessionDetail(@PathVariable Long sessionId) {
        return Result.success(sessionService.getSessionDetail(sessionId));
    }

    /**
     * 更新会话标题
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/title")
    public Result<Void> updateSessionTitle(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateTitleRequest request) {
        sessionService.updateSessionTitle(sessionId, request.title());
        return Result.success(null);
    }

    /**
     * 切换会话置顶状态
     * PUT /api/rag-chat/sessions/{sessionId}/pin
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/pin")
    public Result<Void> togglePin(@PathVariable Long sessionId) {
        sessionService.togglePin(sessionId);
        return Result.success(null);
    }

    /**
     * 更新会话的知识库关联（整体替换）。
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/knowledge-bases")
    public Result<Void> updateSessionKnowledgeBases(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateKnowledgeBasesRequest request) {
        sessionService.updateSessionKnowledgeBases(sessionId, request.knowledgeBaseIds());
        return Result.success(null);
    }

    /**
     * 删除会话（级联删除所有消息）。
     * DELETE /api/rag-chat/sessions/{sessionId}
     */
    @DeleteMapping("/api/rag-chat/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        sessionService.deleteSession(sessionId);
        return Result.success(null);
    }

    /**
     * 发送消息（流式 SSE）
     * 流式响应设计：
     * 1. 先同步保存用户消息和创建 AI 消息占位
     * 2. 返回流式响应
     * 3. 流式完成后通过回调更新消息
     */
    @PostMapping(value = "/api/rag-chat/sessions/{sessionId}/messages/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sendMessageStream(
            @PathVariable Long sessionId,
            @Valid @RequestBody SendMessageRequest request) {

        log.info("收到 RAG 聊天流式请求: sessionId={}, question={}, 线程: {} (虚拟线程: {})",
                sessionId, request.question(), Thread.currentThread(), Thread.currentThread().isVirtual());

        // 1. 准备消息（保存用户消息，创建 AI 消息占位）
        Long messageId = sessionService.prepareStreamMessage(sessionId, request.question());

        // 2. 获取流式响应
        StringBuilder fullContent = new StringBuilder();

        return sessionService.getStreamAnswer(sessionId, request.question())
                .doOnNext(fullContent::append)
                // 使用 ServerSentEvent 包装，转义换行符避免破坏 SSE 格式
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk.replace("\n", "\\n").replace("\r", "\\r"))
                        .build())
                .doOnComplete(() -> {
                    // 3. 流式完成后更新消息内容
                    sessionService.completeStreamMessage(messageId, fullContent.toString());
                    log.info("RAG 聊天流式完成: sessionId={}, messageId={}", sessionId, messageId);
                })
                .doOnError(e -> {
                    // 错误时也保存已接收的内容
                    String content = !fullContent.isEmpty()
                            ? fullContent.toString()
                            : "【错误】回答生成失败：" + e.getMessage();
                    sessionService.completeStreamMessage(messageId, content);
                    log.error("RAG 聊天流式错误: sessionId={}", sessionId, e);
                });
    }
}

