package com.ywy.interviewagentapplication.modules.knowledgebase.service;

import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.infrastructure.mapper.KnowledgeBaseMapper;
import com.ywy.interviewagentapplication.infrastructure.mapper.RagChatMapper;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.RagChatDTO.CreateSessionRequest;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.RagChatDTO.SessionDTO;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.RagChatDTO.SessionDetailDTO;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.RagChatDTO.SessionListItemDTO;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.RagChatMessageEntity;
import com.ywy.interviewagentapplication.modules.knowledgebase.model.RagChatSessionEntity;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.KnowledgeBaseRepository;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.RagChatMessageRepository;
import com.ywy.interviewagentapplication.modules.knowledgebase.repository.RagChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.HashSet;
import java.util.List;

/**
 * RAG 聊天会话服务：会话生命周期管理和流式消息编排。
 *
 * <h3>职责范围</h3>
 * <ul>
 *   <li><b>会话 CRUD</b>：创建、列表、详情、更新标题、置顶切换、更新知识库、删除</li>
 *   <li><b>流式消息编排</b>：占位消息准备（prepare）、上下文构建、完成回写（complete）</li>
 * </ul>
 * RAG 问答的具体实现（检索、改写、LLM 调用）委托给 {@link KnowledgeBaseQueryService}，本 Service 专注会话维度的数据和编排。
 *
 * <h3>流式消息的三步生命周期</h3>
 * <pre>
 * prepareStreamMessage（请求开始时）
 *   ├─ 保存用户消息（completed=false）
 *   ├─ 创建 AI 占位消息（completed=false，content=""）
 *   └─ 返回占位消息 ID
 *        │
 * getStreamAnswer（流式过程中）
 *   └─ 加载历史上下文 → 委托 QueryService 流式生成
 *        │
 * completeStreamMessage（流式结束后）
 *   └─ 将完整回答写回占位消息（completed=true）
 * </pre>
 *
 * <h3>历史上下文的"排除当前轮"处理</h3>
 * {@link #loadHistoryMessages} 查询最近 N+1 条消息，
 * 然后排除最新的一条（当前轮刚保存的用户消息）——
 * 当前问题通过 question 参数单独传入，不应在历史中重复出现。
 * 多取 1 条的技巧避免了"先查历史再保存用户消息"的顺序约束。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatSessionService {

    private final RagChatSessionRepository sessionRepository;
    private final RagChatMessageRepository messageRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseQueryService queryService;
    private final RagChatMapper ragChatMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseQueryProperties queryProperties;

    /**
     * 创建新会话。
     * <p>
     * 知识库存在性校验：{@code findAllById} 返回的实体数与请求 ID 数不一致
     * 说明部分 ID 不存在（重复 ID 会被去重），直接拒绝而非静默忽略，避免用户误以为所有知识库都已关联。
     * <p>
     * 标题生成策略：用户提供则用之；否则根据知识库生成
     * （单库 → 知识库名称；多库 → "N 个知识库对话"）。
     *
     * @param request 创建请求（知识库 ID 列表 + 可选标题）
     * @return 创建后的会话 DTO
     */
    @Transactional
    public SessionDTO createSession(CreateSessionRequest request) {
        // 验证知识库存在
        List<KnowledgeBaseEntity> knowledgeBases = knowledgeBaseRepository
                .findAllById(request.knowledgeBaseIds());

        if (knowledgeBases.size() != request.knowledgeBaseIds().size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部分知识库不存在");
        }

        // 创建会话
        RagChatSessionEntity session = new RagChatSessionEntity();
        session.setTitle(request.title() != null && !request.title().isBlank()
                ? request.title()
                : generateTitle(knowledgeBases));
        session.setKnowledgeBases(new HashSet<>(knowledgeBases));

        session = sessionRepository.save(session);

        log.info("创建 RAG 聊天会话: id={}, title={}", session.getId(), session.getTitle());

        return ragChatMapper.toSessionDTO(session);
    }

    /**
     * 获取会话列表（按置顶状态和更新时间排序：置顶的在前，然后按更新时间降序）。
     * 排序在 Repository 层完成。
     */
    public List<SessionListItemDTO> listSessions() {
        return sessionRepository.findAllOrderByPinnedAndUpdatedAtDesc()
                .stream()
                .map(ragChatMapper::toSessionListItemDTO)
                .toList();
    }

    /**
     * 获取会话详情（包含消息历史）。
     * <p>
     * <b>两次查询避免笛卡尔积</b>：
     * 会话 × 知识库（多对多）和 会话消息（一对多）如果合并为
     * 一次 JOIN 查询，结果行数 = 知识库数 × 消息数（笛卡尔积爆炸）。
     * 分两次查询（各查各的关联），在应用层组装，查询成本可控。
     * <p>
     */
    public SessionDetailDTO getSessionDetail(Long sessionId) {
        // 先加载会话和知识库
        RagChatSessionEntity session = sessionRepository
                .findByIdWithKnowledgeBases(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        // 再单独加载消息（避免笛卡尔积）
        List<RagChatMessageEntity> messages = messageRepository
                .findBySessionIdOrderByMessageOrderAsc(sessionId);

        // 知识库实体列表转换为知识库详情 DTO 列表
        List<KnowledgeBaseListItemDTO> kbDTOs = knowledgeBaseMapper.toListItemDTOList(
                new java.util.ArrayList<>(session.getKnowledgeBases())
        );

        return ragChatMapper.toSessionDetailDTO(session, messages, kbDTOs);
    }

    /**
     * 准备流式消息：保存用户消息 + 创建 AI 消息占位。
     * <p>
     * <b>占位消息的必要性</b>：SSE 流式响应是异步的，
     * 但消息记录需要在流开始前就存在（前端可能中途刷新页面）。
     * 占位消息（completed=false, content=""）让消息列表在流进行中
     * 也能展示"AI 正在回答..."的状态。
     * <p>
     * <b>messageOrder 的分配</b>：基于 messageCount 而非查询 MAX(messageOrder)——
     * messageCount 是维护的冗余字段，读取它省去一次 MAX 聚合查询。
     * 并发场景下可能有竞态（两个流式请求同时取到相同 nextOrder），
     * 但同一会话的并发消息在业务上极少发生（用户一次只问一个问题）。
     *
     * @param sessionId 会话 ID
     * @param question  用户问题
     * @return AI 占位消息的 ID（供流式完成后回写）
     */
    @Transactional
    public Long prepareStreamMessage(Long sessionId, String question) {
        RagChatSessionEntity session = sessionRepository.findByIdWithKnowledgeBases(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        // 获取当前消息数量作为起始顺序
        int nextOrder = session.getMessageCount();

        // 保存用户消息
        RagChatMessageEntity userMessage = new RagChatMessageEntity();
        userMessage.setSession(session);
        userMessage.setType(RagChatMessageEntity.MessageType.USER);
        userMessage.setContent(question);
        userMessage.setMessageOrder(nextOrder);
        userMessage.setCompleted(true);
        messageRepository.save(userMessage);

        // 创建 AI 消息占位（未完成）
        RagChatMessageEntity assistantMessage = new RagChatMessageEntity();
        assistantMessage.setSession(session);
        assistantMessage.setType(RagChatMessageEntity.MessageType.ASSISTANT);
        assistantMessage.setContent("");
        assistantMessage.setMessageOrder(nextOrder + 1);
        assistantMessage.setCompleted(false);
        assistantMessage = messageRepository.save(assistantMessage);

        // 更新会话消息数量
        session.setMessageCount(nextOrder + 2);
        sessionRepository.save(session);

        log.info("准备流式消息: sessionId={}, messageId={}", sessionId, assistantMessage.getId());

        return assistantMessage.getId();
    }

    /**
     * 流式响应完成后保存消息内容到数据库，并更新为完成状态。
     * <p>
     * 被 Controller 的 doOnComplete 和 doOnError 调用。
     * 无论流式正常结束还是异常中断，占位消息都会被完成，
     * 保证数据库中没有永远 completed=false 的孤儿消息。
     *
     * @param messageId AI 占位消息 ID
     * @param content   完整回答内容（或错误提示）
     */
    @Transactional
    public void completeStreamMessage(Long messageId, String content) {
        RagChatMessageEntity message = messageRepository.findById(messageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "消息不存在"));

        message.setContent(content);
        message.setCompleted(true);
        messageRepository.save(message);

        log.info("完成流式消息: messageId={}, contentLength={}", messageId, content.length());
    }

    /**
     * 获取流式回答（带多轮上下文）。
     * <p>
     * 从会话中提取知识库 ID 列表和历史消息，委托给 QueryService。
     * 历史上下文受配置控制（history.enabled），关闭时退化为单轮问答。
     *
     * @param sessionId 会话 ID
     * @param question  用户问题
     * @return LLM 流式响应
     */
    public Flux<String> getStreamAnswer(Long sessionId, String question) {
        RagChatSessionEntity session = sessionRepository.findByIdWithKnowledgeBases(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        List<Long> kbIds = session.getKnowledgeBaseIds();
        List<Message> history = queryProperties.getHistory().isEnabled()
                ? loadHistoryMessages(sessionId) : List.of();

        log.info("加载历史上下文: sessionId={}, historySize={}", sessionId, history.size());
        return queryService.answerQuestionStream(kbIds, question, history);
    }

    /**
     * 更新会话标题
     */
    @Transactional
    public void updateSessionTitle(Long sessionId, String title) {
        RagChatSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        session.setTitle(title);
        sessionRepository.save(session);

        log.info("更新会话标题: sessionId={}, title={}", sessionId, title);
    }

    /**
     * 切换会话置顶状态（置顶 ↔ 取消置顶）。
     * <p>
     * null 值兼容：旧数据 isPinned 可能为 NULL（字段后加），显式转为 false 后再取反。
     */
    @Transactional
    public void togglePin(Long sessionId) {
        RagChatSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        // 处理 null 值（兼容旧数据）
        Boolean currentPinned = session.getIsPinned() != null ? session.getIsPinned() : false;
        session.setIsPinned(!currentPinned);
        sessionRepository.save(session);

        log.info("切换会话置顶状态: sessionId={}, isPinned={}", sessionId, session.getIsPinned());
    }

    /**
     * 更新会话的知识库关联（整体替换）。
     * <p>
     * <b>整体替换语义，而非"增量添加"语义</b>：传入的 ID 列表是新的完整集合。
     * <p>
     * 注意：不校验 ID 数量匹配（与 createSession 不同），不存在的 ID 在 findAllById 中会被静默忽略。
     */
    @Transactional
    public void updateSessionKnowledgeBases(Long sessionId, List<Long> knowledgeBaseIds) {
        RagChatSessionEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        List<KnowledgeBaseEntity> knowledgeBases = knowledgeBaseRepository
                .findAllById(knowledgeBaseIds);

        session.setKnowledgeBases(new HashSet<>(knowledgeBases));
        sessionRepository.save(session);

        log.info("更新会话知识库: sessionId={}, kbIds={}", sessionId, knowledgeBaseIds);
    }

    /**
     * 删除会话（级联删除所有消息）。
     * <p>
     * 先 existsById 检查（不存在，则直接抛出异常），
     * 再 deleteById：JPA 的 cascade + orphanRemoval 自动级联删除消息。
     */
    @Transactional
    public void deleteSession(Long sessionId) {
        if (!sessionRepository.existsById(sessionId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        sessionRepository.deleteById(sessionId);

        log.info("删除会话: sessionId={}", sessionId);
    }

    // ========== 私有方法 ==========

    /**
     * 加载会话中最近的历史消息作为多轮上下文。
     *
     * <h4>排除当前轮消息的技巧</h4>
     * 查询 {@code maxMessages + 1} 条最近的已完成消息。
     * 由于 prepareStreamMessage 先于本方法执行，最新的一条是当前轮刚保存的用户消息。
     * 多取 1 条然后丢弃它，恰好得到"当前轮之前的 N 条历史"。
     * <p>
     * 查询结果按 messageOrder DESC（最近消息在前）：
     * <ol>
     *   <li>subList(1, size) 丢弃第一条（当前轮的用户消息）</li>
     *   <li>reversed() 反转为正序（时间从早到晚）</li>
     * </ol>
     *
     * @param sessionId 会话 ID
     * @return 历史消息列表（Spring AI Message 类型，按时间正序）
     */
    private List<Message> loadHistoryMessages(Long sessionId) {
        int limit = queryProperties.getHistory().getMaxMessages() + 1;
        List<RagChatMessageEntity> recent = messageRepository
                .findRecentCompletedBySessionId(sessionId, PageRequest.of(0, limit));

        if (recent.isEmpty()) {
            return List.of();
        }

        // 查询结果按 messageOrder DESC 排列，最后一条（DESC 首条）是当前轮的 user 消息，排除
        List<RagChatMessageEntity> historyMessages = recent.size() <= 1
                ? List.of()
                : recent.subList(1, recent.size());

        // 反转为正序（时间从早到晚）
        return historyMessages.reversed().stream()
                .map(m -> m.getType() == RagChatMessageEntity.MessageType.USER
                        ? (Message) new UserMessage(m.getContent())
                        : (Message) new AssistantMessage(m.getContent()))
                .toList();
    }

    /**
     * 根据关联的知识库生成默认会话标题。
     * <p>
     * 规则：
     * <ul>
     *   <li>空知识库 → "新对话"</li>
     *   <li>单个知识库 → 知识库名称（如 "Java面试题库"）</li>
     *   <li>多个知识库 → "N 个知识库对话"（如 "3 个知识库对话"）</li>
     * </ul>
     */
    private String generateTitle(List<KnowledgeBaseEntity> knowledgeBases) {
        if (knowledgeBases.isEmpty()) {
            return "新对话";
        }
        if (knowledgeBases.size() == 1) {
            return knowledgeBases.getFirst().getName();
        }
        return knowledgeBases.size() + " 个知识库对话";
    }
}

