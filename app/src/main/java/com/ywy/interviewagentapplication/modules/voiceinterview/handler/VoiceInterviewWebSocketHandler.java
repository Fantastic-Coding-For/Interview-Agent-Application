package com.ywy.interviewagentapplication.modules.voiceinterview.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import com.ywy.interviewagentapplication.modules.voiceinterview.dto.WebSocketControlMessage;
import com.ywy.interviewagentapplication.modules.voiceinterview.dto.WebSocketSubtitleMessage;
import com.ywy.interviewagentapplication.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import com.ywy.interviewagentapplication.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import com.ywy.interviewagentapplication.modules.voiceinterview.config.VoiceInterviewProperties;
import com.ywy.interviewagentapplication.modules.voiceinterview.context.VoiceContextCompressor;
import com.ywy.interviewagentapplication.modules.voiceinterview.service.QwenAsrService;
import com.ywy.interviewagentapplication.modules.voiceinterview.service.QwenTtsService;
import com.ywy.interviewagentapplication.modules.voiceinterview.service.DashscopeLlmService;
import com.ywy.interviewagentapplication.modules.voiceinterview.service.VoiceInterviewService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 语音面试 WebSocket 处理器。
 * <p>
 *
 * <h3>数据处理管线</h3>
 * Processing pipeline: User Audio → STT → LLM → TTS → AI Audio
 * <p>
 * 用户音频(Base64) → QwenAsrService(实时转写) → 字幕/合并缓冲(SessionState)
 *   → [前端 submit] → DashscopeLlmService(流式生成) → 文本/字幕推送
 *   → QwenTtsService(逐句合成) → 有序音频块/WAV → 前端播放
 *
 * <h3>线程模型（三层隔离）</h3>
 * <ul>
 *   <li><b>WebSocket 消息线程</b>（容器管理）：只做解析、状态登记、转发，
 *       绝不执行阻塞操作</li>
 *   <li><b>utteranceMergeScheduler</b>（2 个调度线程）：ASR 就绪检查、
 *       提交重试等轻量定时任务；刻意只给 2 个线程，足够处理</li>
 *   <li><b>voicePipelineExecutor</b>（虚拟线程池）：LLM、TTS、JDBC 等
 *       全部阻塞工作——虚拟线程可无限创建、随阻塞自动挂起，
 *       每个对话轮次一个虚拟线程，天然隔离互不阻塞</li>
 * </ul>
 *
 * <h3>关键机制</h3>
 * <ol>
 *   <li><b>回声抑制</b>：AI 说话期间（aiSpeaking）+ 播放结束后的冷却期
 *       （AI_SPEAK_COOLDOWN_MS=800ms）丢弃麦克风输入，防止扬声器声音
 *       被麦克风拾取后再次触发识别</li>
 *   <li><b>多段合并提交</b>：VAD 会把一句话切成多个 completed 段，
 *       mergeBuffer 累积拼接，由前端 submit（或直接 setMergeBufferDirectly）
 *       触发一次 LLM 调用——话语权交给用户</li>
 *   <li><b>ASR 断线自愈</b>：推流失败 → 重连 → 15 次 × 80ms 轮询重试推块；
 *       就绪检查定时器在 10 秒未就绪时自动重连（最多 2 次）</li>
 *   <li><b>句子级流式 TTS</b>：LLM 生成过程中每检测到一个完整句子
 *       就并发提交一次 TTS，OrderedTtsChunkEmitter 保证音频块按顺序推给前端——
 *       首句音频等待时间从「整个回复生成完」降到「第一句生成完」</li>
 *   <li><b>兜底策略链</b>：分块 TTS 全失败 → 整文 TTS 兜底 → 全部失败 → 纯文本
 *       （面试仍可继续，只是没有声音）</li>
 *   <li><b>超时暂停</b>：无活动 4:30 警告、5:00 暂停并断连（定时扫描）</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewWebSocketHandler extends TextWebSocketHandler implements DisposableBean {

    private final ObjectMapper objectMapper;
    private final QwenAsrService sttService;
    private final QwenTtsService ttsService;
    private final DashscopeLlmService llmService;
    private final VoiceInterviewService interviewService;
    private final VoiceContextCompressor voiceContextCompressor;
    private final VoiceInterviewProperties voiceInterviewProperties;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    /**
     * 合并多段 STT 定稿后再触发 LLM 的延迟调度
     */
    private final ScheduledExecutorService utteranceMergeScheduler = createUtteranceMergeScheduler();

    /**
     * LLM / TTS / JDBC 等阻塞工作全部跑在虚拟线程上，避免占满 utteranceMergeScheduler 的 2 个调度线程。
     */
    private final ExecutorService voicePipelineExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** 会话ID → WebSocket 会话（外层包装了并发发送装饰器，见 afterConnectionEstablished） */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    /** 会话ID → 会话运行时状态（mergeBuffer/处理标志/回声抑制等内存态） */
    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>();
    /** 开场白文本 → 预热好的 WAV 音频（避免每次开场都打一次云端 TTS） */
    private final Map<String, byte[]> openingAudioCache = new ConcurrentHashMap<>();

    /**
     * Activity tracking for pause timeout
     * 活动跟踪（用于暂停超时）
     */
    private final Map<String, Long> lastActivityTime = new ConcurrentHashMap<>();
    /**
     * 4:30
     */
    private static final long WARNING_TIME_MS = (long) (4.5 * 60 * 1000);
    /**
     * 5:00
     */
    private static final long PAUSE_TIMEOUT_MS = 5 * 60 * 1000;
    private static final int WS_SEND_TIME_LIMIT_MS = 10_000;
    private static final int WS_SEND_BUFFER_LIMIT_BYTES = 512 * 1024;
    /** AI 音频播放结束后的冷却期，防止扬声器尾音被麦克风拾取触发 STT */
    private static final long AI_SPEAK_COOLDOWN_MS = 800;
    private static final int MAX_ASR_READY_RETRY = 2;
    private static final long ASR_READY_CHECK_DELAY_SECONDS = 10;
    private static final String DEFAULT_OPENING_QUESTION_ALGORITHM =
            "你好，我是本场面试官。第一个问题：请你口述一道算法题，不写代码，只讲\u300C问题建模、数据结构选型、步骤、复杂度、边界处理\u300D。";
    private static final String DEFAULT_OPENING_QUESTION_BACKEND =
            "你好，我是本场面试官。第一个问题：请用 1 分钟介绍一个你深度参与的项目，按三点回答：业务目标、你负责的核心模块、核心技术栈。说完我会立刻追问一个关键技术决策。";

    /**
     * 创建语句合并调度器。
     *
     * <ul>
     *   <li>核心数 2：合并窗口与就绪检查都是轻量任务，2 个线程足够</li>
     *   <li>daemon 线程：不阻塞 JVM 正常退出</li>
     *   <li>RemoveOnCancelPolicy：取消的任务立即出队，防止无引用对象滞留</li>
     *   <li>关闭后不执行存量延迟任务：应用停止时不再触发任何回调</li>
     * </ul>
     */
    private static ScheduledExecutorService createUtteranceMergeScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "voice-utterance-merge");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    /**
     * 应用启动后预热开场白音频缓存（可选，默认关闭）。
     *
     * <p>在虚拟线程中执行避免阻塞启动；预热所有技能开场白 + 算法/后端默认开场白。
     * 默认关闭的原因：启动即产生多条云端 TTS 调用（计费 + 延迟），
     * 仅在「开场白确定不变、追求首次体验」的部署中开启。
     */
    @PostConstruct
    public void warmupOpeningAudioCache() {
        if (!voiceInterviewProperties.isOpeningAudioWarmupEnabled()) {
            log.info("Opening audio cache warmup is disabled");
            return;
        }
        voicePipelineExecutor.execute(() -> {
            try {
                VoiceInterviewProperties.OpeningConfig opening = voiceInterviewProperties.getOpening();
                if (opening == null) {
                    return;
                }
                LinkedHashSet<String> allTemplates = new LinkedHashSet<>();
                if (opening.getSkillQuestions() != null) {
                    allTemplates.addAll(opening.getSkillQuestions().values());
                }
                allTemplates.add(opening.getAlgorithmQuestion());
                allTemplates.add(opening.getBackendQuestion());
                for (String template : allTemplates) {
                    preloadOpeningAudio(template);
                }
                log.info("Opening audio cache warmed: {} entries", openingAudioCache.size());
            } catch (Exception e) {
                log.warn("Opening audio cache warmup skipped: {}", e.getMessage());
            }
        });
    }

    /**
     * 合成并缓存一条开场白音频；失败静默跳过（运行时仍可走 getOpeningWavAudio 的懒加载路径）。
     */
    private void preloadOpeningAudio(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        byte[] wavAudio = synthesizeToWav(text);
        if (wavAudio.length > 0) {
            openingAudioCache.put(text, wavAudio);
        }
    }

    /**
     * WebSocket 建连回调：注册会话、启动 ASR、发欢迎语、自动开场。
     *
     * <h3>为什么用 ConcurrentWebSocketSessionDecorator</h3>
     * 多线程（STT 回调线程、虚拟线程管线）会并发向同一会话 sendMessage；
     * 裸 WebSocketSession 非线程安全。装饰器内部加锁并设置
     * 发送时限（10s）与缓冲上限（512KB）——发送阻塞超时会直接抛异常，
     * 防止一条慢消息拖死整个会话。
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = extractSessionId(session);

        // Increase message size limits for audio streaming
        // 1 second of PCM audio @ 16kHz, 16-bit = ~32KB raw, ~42KB base64
        // Set limit to 256KB to allow some buffer and multiple messages
        session.setTextMessageSizeLimit(256 * 1024); // 256KB
        session.setBinaryMessageSizeLimit(256 * 1024); // 256KB

        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
                session, WS_SEND_TIME_LIMIT_MS, WS_SEND_BUFFER_LIMIT_BYTES
        );

        sessions.put(sessionId, safeSession);
        sessionStates.put(sessionId, new SessionState());
        lastActivityTime.put(sessionId, System.currentTimeMillis());
        log.info("WebSocket connection established for session: {}", sessionId);

        try {
            startDashScopeStt(sessionId, safeSession);

            // 发送欢迎消息
            sendMessage(safeSession, createWelcomeMessage());
            // 自动开场：面试官先说开场语并直接提出第一个问题（仅首次连接、无历史消息时触发）
            triggerOpeningQuestionIfNeeded(sessionId, safeSession);
        } catch (Exception e) {
            log.error("Error establishing WebSocket connection for session {}", sessionId, e);
            sendError(safeSession, "初始化语音识别失败: " + e.getMessage());
        }
    }

    /**
     * 创建欢迎消息：前端据此显示「已连接」，并知道可以开始说话。
     */
    private String createWelcomeMessage() {
        return toJson(Map.of(
                "type", "control",
                "action", "welcome",
                "message", "连接成功，准备开始语音面试",
                "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * 对象转 JSON；序列化失败降级为 "{}"（消息体损坏不致命，绝不因序列化异常断开链路）。
     */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("Error serializing JSON", e);
            return "{}";
        }
    }

    /**
     * 发送文本消息到会话（所有推送的公共出口，推送异常只记日志）。
     */
    private void sendMessage(WebSocketSession session, String message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(message));
                log.debug("Message sent to session: {}", message.substring(0, Math.min(100, message.length())));
            } else {
                log.warn("Session is closed, cannot send message");
            }
        } catch (Exception e) {
            log.error("Error sending message to session", e);
        }
    }

    /**
     * 自动开场：无历史消息的首次连接，主动抛出开场白 + 第一个问题。
     *
     * <h3>「先落库再推送」</h3>
     * 开场问题先存库（saveMessage）再推前端——若顺序颠倒，用户秒答时
     * 回填逻辑（fillLatestUnansweredQuestion）找不到该提问行，
     * 会产生「孤儿回答」。落库先行保证一问一答的不变式在任何时序下成立。
     */
    private void triggerOpeningQuestionIfNeeded(String sessionId, WebSocketSession session) {
        voicePipelineExecutor.execute(() -> {
            try {
                if (session == null || !session.isOpen()) {
                    return;
                }

                VoiceInterviewSessionEntity sessionEntity = getSessionEntity(sessionId);
                if (sessionEntity == null) {
                    log.warn("Session entity not found when sending opening question: {}", sessionId);
                    return;
                }

                List<String> history = getHistory(sessionId, sessionEntity.getLlmProvider());
                if (history != null && !history.isEmpty()) {
                    // 已有历史对话（如重连/恢复），不重复开场
                    return;
                }

                String aiReply = buildOpeningQuestion(sessionEntity);
                if (aiReply == null || aiReply.isBlank()) {
                    return;
                }

                if (!session.isOpen()) {
                    return;
                }

                // 先落库再推前端，确保用户提交时 DB 中已有该条消息
                saveMessage(sessionId, null, aiReply);
                sendTextMessage(session, aiReply, true);

                // 语音随后下发
                byte[] wavAudio = getOpeningWavAudio(aiReply);
                if (wavAudio.length > 0 && session.isOpen()) {
                    sendAudio(session, wavAudio, aiReply);
                }

                log.info("Opening question sent for session {}", sessionId);
            } catch (Exception e) {
                log.error("Failed to send opening question for session {}", sessionId, e);
            }
        });
    }

    /**
     * 取开场白音频：先查预热缓存，未命中则现场合成并回填缓存。
     */
    private byte[] getOpeningWavAudio(String text) {
        byte[] cached = openingAudioCache.get(text);
        if (cached != null && cached.length > 0) {
            return cached;
        }
        byte[] wav = synthesizeToWav(text);
        if (wav.length > 0) {
            openingAudioCache.put(text, wav);
        }
        return wav;
    }

    /**
     * TTS 合成 + PCM→WAV 转换的一站式封装；失败返回空数组（调用方静默跳过语音）。
     */
    private byte[] synthesizeToWav(String text) {
        byte[] pcm = ttsService.synthesize(text);
        if (pcm == null || pcm.length == 0) {
            return new byte[0];
        }
        return convertPcmToWav(pcm);
    }

    /**
     * 按技能方向选择开场问题（三级回退）：
     * skillQuestions 精确匹配 → 算法技能列表命中 → 通用后端开场白 →
     * （配置为空时）内置默认文案。每一级都判空/判白，保证永不返回空白。
     */
    private String buildOpeningQuestion(VoiceInterviewSessionEntity sessionEntity) {
        String skillId = sessionEntity.getSkillId() != null ? sessionEntity.getSkillId() : "";
        VoiceInterviewProperties.OpeningConfig opening = voiceInterviewProperties.getOpening();
        Map<String, String> skillQuestions = opening != null ? opening.getSkillQuestions() : null;
        if (skillQuestions != null) {
            String bySkill = skillQuestions.get(skillId);
            if (bySkill != null && !bySkill.isBlank()) {
                return bySkill;
            }
        }
        List<String> algorithmSkills = opening != null && opening.getAlgorithmSkills() != null
                ? opening.getAlgorithmSkills()
                : List.of();

        if (algorithmSkills.contains(skillId)) {
            String configured = opening != null ? opening.getAlgorithmQuestion() : null;
            return configured != null && !configured.isBlank()
                    ? configured
                    : DEFAULT_OPENING_QUESTION_ALGORITHM;
        }
        String configured = opening != null ? opening.getBackendQuestion() : null;
        return configured != null && !configured.isBlank()
                ? configured
                : DEFAULT_OPENING_QUESTION_BACKEND;
    }

    /**
     * 用户音频消息处理入口：路由到音频处理或控制处理。
     *
     * <p>音频消息量大，日志降到 trace 级（每块都打 info 会刷爆日志）；
     * 超过 200KB 的消息单独告警——正常音频块约 40KB，大消息说明协议异常。
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = extractSessionId(session);

        try {
            JsonNode msg = objectMapper.readTree(message.getPayload());
            String type = msg.get("type").asText();
            int messageSize = message.getPayload().length();
            int messageSizeKB = messageSize / 1024;
            if ("audio".equals(type)) {
                log.trace("[WebSocket] Received audio: sessionId={}, size={}KB", sessionId, messageSizeKB);
            } else {
                log.info("[WebSocket] Received message: sessionId={}, type={}, size={}KB ({} bytes)",
                        sessionId, type, messageSizeKB, messageSize);
            }

            if (messageSizeKB > 200) {
                log.warn("[WebSocket] Large message detected: {}KB", messageSizeKB);
            }

            // Update last activity time for pause timeout detection
            // 更新最后活动时间（用于暂停超时检测）
            lastActivityTime.put(sessionId, System.currentTimeMillis());

            switch (type) {
                case "audio":
                    String audioData = msg.has("data") ? msg.get("data").asText() : null;
                    if (audioData != null && !audioData.isEmpty()) {
                        handleUserAudio(sessionId, audioData);
                    } else {
                        log.warn("Received audio message without data");
                    }
                    break;
                case "control":
                    try {
                        handleControl(sessionId, objectMapper.treeToValue(msg, WebSocketControlMessage.class));
                    } catch (Exception e) {
                        log.error("Error handling control message for session {}", sessionId, e);
                        sendError(session, "控制消息处理失败: " + e.getMessage());
                    }
                    break;
                default:
                    log.warn("Unknown message type: {} for session {}", type, sessionId);
            }

        } catch (Exception e) {
            log.error("Error handling message for session {}", sessionId, e);
            sendError(session, "消息处理失败: " + e.getMessage());
        }
    }

    /**
     * 会话关闭清理：先拒收新任务（shutdownNow），再限时等待管线线程收尾。
     */
    @Override
    public void destroy() {
        voicePipelineExecutor.shutdownNow();
        utteranceMergeScheduler.shutdownNow();
        try {
            if (!voicePipelineExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("voicePipelineExecutor did not terminate within 5s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 会话关闭清理：移除会话、中断进行中的管线线程、停止 ASR、兜底结束会话。
     *
     * <h3>中断管线线程的意义</h3>
     * LLM/TTS 调用阻塞在虚拟线程上，用户断连后继续跑完纯属浪费；
     * interrupt 让可中断的阻塞（如闩等待、HTTP 调用）尽快退出，
     * 并在 triggerLlmResponse 里通过 session.isOpen() 检查是否已经关闭。
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = extractSessionId(session);
        try {
            sessions.remove(sessionId);
            SessionState removedState = sessionStates.remove(sessionId);
            if (removedState != null) {
                Thread t = removedState.getProcessingThread();
                if (t != null) {
                    t.interrupt();
                }
            }
            lastActivityTime.remove(sessionId);

            // Stop STT transcription
            sttService.stopTranscription(sessionId);
            log.info("WebSocket connection closed for session: {}, status: {}", sessionId, status);

            // WebSocket 异常断开时自动结束会话，防止状态永远停留在 IN_PROGRESS
            try {
                interviewService.endSessionIfInProgress(sessionId);
            } catch (Exception endEx) {
                log.warn("Failed to auto-end session {} after disconnect: {}", sessionId, endEx.getMessage());
            }
        } catch (Exception e) {
            log.error("Error cleaning up session {} after close", sessionId, e);
        }
    }

    /**
     * 传输层错误只记日志：连接级错误由 onClose 路径统一清理。
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket transport error for session {}", extractSessionId(session), exception);
    }

    /**
     * 判断 ASR 异常是否可通过「重连」恢复（无会话、append 失败等均可重连 ASR）。
     *
     * <p>只有两类可恢复：无活动会话（连接已被服务端关掉）与推流失败。
     * 其他异常（如认证失败）重连也无济于事，直接上抛走错误提示。
     */
    private static boolean shouldRecoverAsrConnection(IllegalStateException ex) {
        String m = ex.getMessage();
        if (m == null) {
            return false;
        }
        return m.contains("No active session") || m.contains("ASR append failed");
    }

    /**
     * 判断是否「连接尚未就绪」——就绪前推块是可预期的时间窗问题，
     * 直接丢弃本块即可（等下一块到达时连接大概率已就绪）。
     */
    private static boolean isAsrNotReady(IllegalStateException ex) {
        String m = ex.getMessage();
        return m != null && m.contains("ASR session not ready");
    }

    /**
     * 启动 ASR 识别（绑定结果回调），并调度「10 秒未就绪检查」。
     *
     * <p>回调全部转发到本类统一处理：final → 合并缓冲，partial → 实时字幕，
     * ready → 前端提示，error → 用户可见错误。
     */
    private void startDashScopeStt(String sessionId, WebSocketSession session) {
        sttService.startTranscription(
                sessionId,
                text -> handleSttResult(sessionId, text, true),
                text -> handleSttResult(sessionId, text, false),
                () -> sendAsrReady(session),
                error -> {
                    log.error("STT error for session {}", sessionId, error);
                    sendError(session, "语音识别失败: " + error.getMessage());
                }
        );

        scheduleAsrReadyCheck(sessionId, session, 0);
    }

    /**
     * 调度一次「ASR 就绪检查」（延迟 10 秒执行）。
     */
    private void scheduleAsrReadyCheck(String sessionId, WebSocketSession session, int retryCount) {
        utteranceMergeScheduler.schedule(
                () -> checkAsrReadyOrRetry(sessionId, session, retryCount),
                ASR_READY_CHECK_DELAY_SECONDS,
                TimeUnit.SECONDS
        );
    }

    /**
     * ASR 就绪检查：未就绪则重连并重新调度检查，最多重试 MAX_ASR_READY_RETRY 次。
     *
     * <p>注意「未就绪≠连接失败」：网络慢时连接可能 10 秒后才就绪；
     * 重连会给用户明确提示（asr_reconnecting），避免静默卡死。
     * 会话已关闭则直接放弃（用户已经走了）。
     */
    private void checkAsrReadyOrRetry(String sessionId, WebSocketSession session, int retryCount) {
        if (session == null || !session.isOpen() || sttService.isReady(sessionId)) {
            return;
        }

        if (retryCount < MAX_ASR_READY_RETRY) {
            int nextRetry = retryCount + 1;
            log.warn("[Session: {}] ASR not ready after {}s, retrying ({}/{})",
                    sessionId, ASR_READY_CHECK_DELAY_SECONDS, nextRetry, MAX_ASR_READY_RETRY);
            sendAsrStatus(session, "asr_reconnecting", "语音识别连接较慢，正在自动重连");
            restartDashScopeStt(sessionId);
            scheduleAsrReadyCheck(sessionId, session, nextRetry);
            return;
        }

        log.warn("[Session: {}] ASR still not ready after {} retries", sessionId, retryCount);
        sendError(session, "语音识别连接准备超时，请检查语音服务配置或稍后重试");
    }

    /**
     * DashScope ASR 断线后重连（回调与首次 start 一致）
     */
    private void restartDashScopeStt(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        sttService.restartTranscription(
                sessionId,
                text -> handleSttResult(sessionId, text, true),
                text -> handleSttResult(sessionId, text, false),
                () -> sendAsrReady(session),
                error -> {
                    log.error("STT error for session {}", sessionId, error);
                    sendError(session, "语音识别失败: " + error.getMessage());
                }
        );
    }

    /**
     * 处理用户音频块：Base64 解码后推给 ASR。
     *
     * <h3>异常分诊（三段式）</h3>
     * <ol>
     *   <li><b>未就绪</b>：静默丢弃本块（时间窗问题，自愈）</li>
     *   <li><b>可重连</b>：重启 ASR 连接后最多 15 次 × 80ms 轮询重推本块
     *       （轮询上限约 1.2 秒，超过则提示用户刷新——长时间重试会积压后续音频块）</li>
     *   <li><b>其他</b>：直接上抛，走用户可见错误提示</li>
     * </ol>
     */
    private void handleUserAudio(String sessionId, String base64Audio) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null) {
            log.warn("Session not found: {}", sessionId);
            return;
        }

        // AI 正在说话或处于回声冷却期时，丢弃麦克风输入，防止回声触发 LLM
        SessionState state = sessionStates.get(sessionId);
        if (state != null && state.isAiSpeakingOrCooldown()) {
            return;
        }

        try {
            byte[] audioData = Base64.getDecoder().decode(base64Audio);
            log.debug("Received audio data for session {}, size: {} bytes", sessionId, audioData.length);

            try {
                sttService.sendAudio(sessionId, audioData);
            } catch (IllegalStateException ex) {
                if (isAsrNotReady(ex)) {
                    log.debug("[Session: {}] Dropping audio chunk before ASR ready", sessionId);
                    return;
                } else if (shouldRecoverAsrConnection(ex)) {
                    log.warn("[Session: {}] ASR send failed ({}), restarting DashScope and retrying chunk",
                            sessionId, ex.getMessage() != null ? ex.getMessage() : "unknown");
                    restartDashScopeStt(sessionId);
                    boolean sent = false;
                    for (int i = 0; i < 15; i++) {
                        try {
                            Thread.sleep(80);
                            sttService.sendAudio(sessionId, audioData);
                            sent = true;
                            break;
                        } catch (IllegalStateException retry) {
                            if (isAsrNotReady(retry)) {
                                continue;
                            }
                            if (!shouldRecoverAsrConnection(retry)) {
                                throw retry;
                            }
                        }
                    }
                    if (!sent) {
                        log.error("[Session: {}] ASR still down after restart", sessionId);
                        sendError(session, "语音识别连接中断，请刷新页面后重试");
                    }
                } else {
                    throw ex;
                }
            }

        } catch (Exception e) {
            log.error("Error handling user audio for session {}", sessionId, e);
            String errorMessage = getErrorMessage(e);
            sendError(session, errorMessage);
        }
    }

    /**
     * STT 结果处理：
     * <ul>
     *   <li><b>partial</b>：合并缓冲快照 + 实时 partial 拼出字幕预览，isFinal=false</li>
     *   <li><b>final</b>：追加进 mergeBuffer（多段拼接）；
     *       是否推送 LLM 由前端 submit 决定（见 flushMergedUtteranceToLlm）</li>
     * </ul>
     *
     * <h3>迟到结果的丢弃</h3>
     * 用户已提交（processing=true）或 AI 正在说话时，上一轮的 partial/final
     * 可能才到达（ASR 延迟）——直接丢弃，防止污染下一轮字幕与合并缓冲。
     */
    private void handleSttResult(String sessionId, String recognizedText, boolean isFinalSegment) {
        WebSocketSession session = sessions.get(sessionId);
        SessionState state = sessionStates.get(sessionId);

        if (session == null || state == null) {
            log.warn("Session or state not found: {}", sessionId);
            return;
        }

        // 用户已提交或 AI 正在回答时，丢弃上一轮迟到的 partial/final，防止污染下一轮字幕。
        if (state.isProcessing().get() || state.isAiSpeakingOrCooldown()) {
            log.debug("Discarding late STT result for session {}, final={}: {}",
                    sessionId, isFinalSegment, recognizedText);
            return;
        }

        if (!isFinalSegment) {
            state.markSttActivity();
            sendSubtitle(session, state.getMergeBufferPreviewWithPartial(recognizedText), false);
            return;
        }

        log.debug("STT final segment for session {}: {}", sessionId, recognizedText);
        incrementCounter("app.voice.interview.asr.final_segments", "status", "received");

        // 合并多次 VAD 切段，只更新实时字幕；是否提交给 LLM 由前端手动 submit 控制
        state.appendFinalSttSegment(recognizedText);
        sendSubtitle(session, state.getMergeBufferPreview(), false);
    }

    /**
     * 手动提交：获取 mergeBuffer 中累积的用户文本并触发 LLM 管线。
     *
     * <h3>CAS 互斥 + 延迟重试</h3>
     * isProcessing 用 compareAndSet 保证同一会话同时只有一个 LLM 管线在跑；
     * 已在处理时（例如前端连点 submit）不丢弃，而是 400ms 后重试——
     * 等上一轮处理完自然接手。管线本身在虚拟线程执行，立即释放调度器线程。
     */
    private void flushMergedUtteranceToLlm(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        SessionState state = sessionStates.get(sessionId);
        if (session == null || state == null || !session.isOpen()) {
            return;
        }
        if (!state.isProcessing().compareAndSet(false, true)) {
            utteranceMergeScheduler.schedule(
                    () -> flushMergedUtteranceToLlm(sessionId),
                    400,
                    TimeUnit.MILLISECONDS);
            return;
        }
        long mergeStartAt = state.getMergeStartedAt();
        String userText = state.takeMergeBufferAndClear();
        if (userText == null || userText.trim().isEmpty()) {
            state.isProcessing().set(false);
            return;
        }
        long mergeWaitMs = Math.max(0, System.currentTimeMillis() - mergeStartAt);
        recordTimerMillis("app.voice.interview.asr.merge_wait", mergeWaitMs, "status", "success");
        state.setAccumulatedText(userText);
        log.info("Merged user utterance for session {}, triggering LLM (length {})", sessionId, userText.length());

        // 提交到虚拟线程执行阻塞的 LLM+TTS 管线，立即释放调度器线程
        voicePipelineExecutor.execute(() -> {
            state.setProcessingThread(Thread.currentThread());
            try {
                triggerLlmResponse(sessionId, session, state);
            } finally {
                state.isProcessing().set(false);
                state.setProcessingThread(null);
            }
        });
    }

    /**
     * 触发一轮完整的「LLM 生成 + 文本/字幕推送 + TTS 音频」管线。
     *
     * <h3>两条路径</h3>
     * <ul>
     *   <li><b>流式（默认）</b>：LLM 逐 token 生成 → 实时文本推送 + 句子级并发 TTS
     *       （chunkedAudioEnabled=true 时走 OrderedTtsChunkEmitter 有序推块，
     *       false 时收集全部 PCM 合并成一个完整音频）</li>
     *   <li><b>非流式</b>：完整生成后一次 TTS 合成（最简路径，延迟最高）</li>
     * </ul>
     *
     * <h3>兜底策略链（流式路径）</h3>
     * 分块 TTS 零产出 → 整文 TTS 兜底 → 仍失败 → 仅文本（面试继续）。
     * 任何一步都要先检查 session.isOpen()：用户在等待期间断连则丢弃全部结果。
     *
     * <h3>指标埋点</h3>
     * 首字延迟、LLM 耗时、TTS 耗时、整轮耗时、失败计数等——全部走 Micrometer，
     * 是线上定位「说话到回答」端到端时延瓶颈的关键数据。
     */
    private void triggerLlmResponse(String sessionId, WebSocketSession session, SessionState state) {
        long turnStartNanos = System.nanoTime();
        state.aiSpeaking.set(true);
        try {
            if (!session.isOpen()) {
                log.warn("WebSocket session is closed, skipping LLM response for session {}", sessionId);
                return;
            }

            String userText = state.getAccumulatedText();
            if (userText == null || userText.trim().isEmpty()) {
                log.warn("Empty user text, skipping LLM response");
                return;
            }

            log.info("Getting LLM response for session {}, text: {}", sessionId, userText);

            VoiceInterviewSessionEntity sessionEntity = getSessionEntity(sessionId);
            if (sessionEntity == null) {
                log.error("Session entity not found for session {}, cannot generate LLM response", sessionId);
                sendError(session, "会话不存在，请重新开始面试");
                return;
            }

            List<String> conversationHistory = getHistory(sessionId, sessionEntity.getLlmProvider());

            long llmStartNanos = System.nanoTime();
            AtomicLong firstTokenAtNanos = new AtomicLong(0);
            boolean streamEnabled = voiceInterviewProperties.isLlmStreamingEnabled();
            String aiReply;

            if (streamEnabled) {
                // 句子级并发 TTS：LLM 流式输出期间每检测到一个完整句子就启动 TTS
                // Semaphore 限制单会话并发合成数：TTS 服务对并发连接有限制，无限并发会把 DashScope 连接打满拖垮全会话
                Semaphore ttsSemaphore = new Semaphore(
                        Math.max(1, voiceInterviewProperties.getMaxConcurrentTtsPerSession()));
                boolean chunkedEnabled = voiceInterviewProperties.isChunkedAudioEnabled();
                long ttsTimeoutSec = Math.max(5, voiceInterviewProperties.getTtsTimeoutSeconds());
                OrderedTtsChunkEmitter chunkEmitter = chunkedEnabled
                        ? new OrderedTtsChunkEmitter(sessionId, session, ttsSemaphore, ttsTimeoutSec)
                        : null;
                List<CompletableFuture<byte[]>> ttsFutures = new ArrayList<>();

                aiReply = llmService.chatStreamSentences(
                        userText,
                        partialText -> {
                            if (partialText == null || partialText.isBlank() || !session.isOpen()) {
                                return;
                            }
                            if (firstTokenAtNanos.compareAndSet(0L, System.nanoTime())) {
                                recordTimerSinceNanos(
                                        "app.voice.interview.llm.first_token_latency",
                                        llmStartNanos,
                                        "status", "success"
                                );
                            }
                            sendTextMessage(session, partialText, false);
                        },
                        sentence -> {
                            if (sentence == null || sentence.isBlank()) {
                                return;
                            }
                            if (chunkEmitter != null) {
                                chunkEmitter.submit(sentence);
                                return;
                            }
                            // 合并模式：逐句并发生成，稍后统一收集合并
                            ttsSemaphore.acquireUninterruptibly();
                            CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
                                try {
                                    return ttsService.synthesize(sentence);
                                } finally {
                                    ttsSemaphore.release();
                                }
                            }, voicePipelineExecutor);
                            ttsFutures.add(future);
                        },
                        sessionEntity,
                        conversationHistory
                );

                recordTimerSinceNanos("app.voice.interview.llm.duration", llmStartNanos, "status", "success");
                incrementCounter("app.voice.interview.llm.calls", "status", "success", "streaming", "true");
                log.info("LLM response for session {}: '{}'", sessionId, aiReply);

                if (!session.isOpen()) {
                    log.warn("WebSocket closed during LLM processing, discarding response for session {}", sessionId);
                    return;
                }

                sendSubtitle(session, userText, true);
                sendTextMessage(session, aiReply, true);
                saveMessage(sessionId, userText, aiReply);

                // 按顺序收集所有 TTS 结果（带超时，防止单句 TTS 挂死阻塞整条管道）
                if (chunkEmitter != null) {
                    long ttsStartNanos = System.nanoTime();
                    chunkEmitter.finish();
                    int emittedChunks = chunkEmitter.awaitCompletion();
                    recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success");
                    if (emittedChunks == 0 && session.isOpen()) {
                        log.info("[Session: {}] Streaming TTS produced no chunks, falling back to full-text TTS",
                                sessionId);
                        try {
                            byte[] fallbackPcm = ttsService.synthesize(aiReply);
                            if (fallbackPcm != null && fallbackPcm.length > 0) {
                                sendAudio(session, convertPcmToWav(fallbackPcm), aiReply);
                            }
                        } catch (Exception e) {
                            log.warn("[Session: {}] Fallback TTS failed: {}", sessionId, e.getMessage());
                        }
                    }
                } else if (!ttsFutures.isEmpty()) {
                    long ttsStartNanos = System.nanoTime();
                    // 合并模式：收集所有 PCM 后合并为一个完整音频
                    List<byte[]> pcmChunks = new ArrayList<>();
                    int totalSize = 0;
                    int failedCount = 0;
                    boolean audioSentByFallback = false;
                    for (CompletableFuture<byte[]> f : ttsFutures) {
                        try {
                            byte[] pcm = f.get(ttsTimeoutSec, TimeUnit.SECONDS);
                            if (pcm != null && pcm.length > 0) {
                                pcmChunks.add(pcm);
                                totalSize += pcm.length;
                            }
                        } catch (Exception e) {
                            f.cancel(true);
                            failedCount++;
                            log.warn("[Session: {}] TTS future failed for one sentence: {}", sessionId, e.getMessage());
                        }
                    }
                    recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success");

                    if (!session.isOpen()) {
                        log.warn("WebSocket closed during TTS processing, discarding audio for session {}", sessionId);
                        return;
                    }

                    // 有句子级 TTS 失败且无成功结果时，用完整文本做一次兜底 TTS
                    if (totalSize == 0 && failedCount > 0 && session.isOpen()) {
                        log.info("[Session: {}] All {} sentence TTS calls failed, falling back to full-text TTS",
                                sessionId, failedCount);
                        try {
                            byte[] fallbackPcm = ttsService.synthesize(aiReply);
                            if (fallbackPcm != null && fallbackPcm.length > 0) {
                                byte[] wavAudio = convertPcmToWav(fallbackPcm);
                                log.info("[Session: {}] Fallback TTS succeeded, WAV size: {} bytes",
                                        sessionId, wavAudio.length);
                                sendAudio(session, wavAudio, aiReply);
                                audioSentByFallback = true;
                            }
                        } catch (Exception e) {
                            log.warn("[Session: {}] Fallback TTS also failed: {}", sessionId, e.getMessage());
                        }
                    }

                    if (!audioSentByFallback) {
                        if (totalSize > 0 && session.isOpen()) {
                            byte[] mergedPcm = new byte[totalSize];
                            int offset = 0;
                            for (byte[] chunk : pcmChunks) {
                                System.arraycopy(chunk, 0, mergedPcm, offset, chunk.length);
                                offset += chunk.length;
                            }
                            byte[] wavAudio = convertPcmToWav(mergedPcm);
                            log.info("[Session: {}] Sending merged audio - {} sentences, WAV size: {} bytes",
                                    sessionId, pcmChunks.size(), wavAudio.length);
                            sendAudio(session, wavAudio, aiReply);
                        } else {
                            log.error("[Session: {}] All TTS calls returned empty audio", sessionId);
                            incrementCounter("app.voice.interview.tts.empty_audio", "status", "empty");
                        }
                    }
                }
            } else {
                aiReply = llmService.chat(userText, sessionEntity, conversationHistory);
                recordTimerSinceNanos("app.voice.interview.llm.duration", llmStartNanos, "status", "success");
                incrementCounter("app.voice.interview.llm.calls", "status", "success", "streaming", "false");
                log.info("LLM response for session {}: '{}'", sessionId, aiReply);

                if (!session.isOpen()) {
                    log.warn("WebSocket closed during LLM processing, discarding response for session {}", sessionId);
                    return;
                }

                sendSubtitle(session, userText, true);
                sendTextMessage(session, aiReply, true);
                saveMessage(sessionId, userText, aiReply);

                long ttsStartNanos = System.nanoTime();
                log.info("[Session: {}] Starting TTS synthesis for text (length: {})",
                        sessionId, aiReply.length());
                byte[] aiAudio = ttsService.synthesize(aiReply);
                recordTimerSinceNanos("app.voice.interview.tts.duration", ttsStartNanos, "status", "success");

                if (!session.isOpen()) {
                    return;
                }

                if (aiAudio == null || aiAudio.length == 0) {
                    log.error("[Session: {}] TTS returned empty audio", sessionId);
                    incrementCounter("app.voice.interview.tts.empty_audio", "status", "empty");
                } else {
                    byte[] wavAudio = convertPcmToWav(aiAudio);
                    sendAudio(session, wavAudio, aiReply);
                }
            }

            state.setAccumulatedText("");
            recordTimerSinceNanos("app.voice.interview.turn.duration", turnStartNanos, "status", "success");
            incrementCounter("app.voice.interview.turn.completed", "status", "success");

        } catch (Exception e) {
            log.error("Error triggering LLM response for session {}", sessionId, e);
            recordTimerSinceNanos("app.voice.interview.turn.duration", turnStartNanos, "status", "failure");
            incrementCounter("app.voice.interview.turn.completed", "status", "failure");
            incrementCounter("app.voice.interview.errors", "stage", "turn");
            if (session.isOpen()) {
                sendError(session, "AI响应失败: " + e.getMessage());
            }
        } finally {
            // 冷却期从「本轮结束」起算：AI 说完后 800ms 内麦克风输入一律丢弃，
            // 防止扬声器尾音被录入触发回声循环
            state.aiSpeaking.set(false);
            state.aiSpeakEndAt.set(System.currentTimeMillis() + AI_SPEAK_COOLDOWN_MS);
        }
    }

    /**
     * 将异常转为用户友好的错误文案。先看 cause 是否存在，按阿里云特征分类定义文案，最后通过默认错误信息兜底。
     */
    private String getErrorMessage(Exception e) {
        Throwable cause = e.getCause();

        // Check for specific Aliyun errors
        if (cause != null) {
            String message = cause.getMessage();
            if (message != null) {
                if (message.contains("403") || message.contains("ACCESS_DENIED")) {
                    return "阿里云语音服务认证失败：AccessKey 无效或已过期。请在 .env 文件中配置正确的 ALIYUN_ACCESS_KEY";
                }
                if (message.contains("timeout") || message.contains("channel inactive")) {
                    return "阿里云语音服务连接超时。请检查网络连接或稍后重试";
                }
            }
        }

        // Default error message
        return "语音处理失败：" + e.getMessage();
    }

    /**
     * 控制消息处理。submit 的特殊性：
     * 若前端在 submit 时同时带来了文本（语音识别不准时的手输修正），
     * 直接覆盖 mergeBuffer——手输文本的优先级高于 ASR 累积结果。
     */
    private void handleControl(String sessionId, WebSocketControlMessage control) {
        log.info("Control message for session {}: action={}, phase={}",
                sessionId, control.getAction(), control.getPhase());

        switch (control.getAction()) {
            case "submit":
                if (control.getData() != null) {
                    Object textObj = control.getData().get("text");
                    if (textObj instanceof String text && !text.isBlank()) {
                        SessionState state = sessionStates.get(sessionId);
                        if (state != null) {
                            state.setMergeBufferDirectly(text);
                        }
                    }
                }
                flushMergedUtteranceToLlm(sessionId);
                break;
            case "end_interview":
                interviewService.endSession(sessionId);
                break;
            case "start_phase":
                interviewService.startPhase(sessionId, control.getPhase());
                break;
        }
    }

    /**
     * 推送字幕消息（用户侧与 AI 侧共用）。
     */
    private void sendSubtitle(WebSocketSession session, String text, boolean isFinal) {
        WebSocketSubtitleMessage subtitle = WebSocketSubtitleMessage.builder()
                .type("subtitle")
                .text(text)
                .isFinal(isFinal)
                .build();
        sendMessage(session, toJson(subtitle));
    }

    /**
     * 推送完整音频（Base64 WAV）：type=audio 单条消息，前端拿到即播。
     */
    private void sendAudio(WebSocketSession session, byte[] audio, String text) {
        if (!session.isOpen()) {
            return;
        }
        String base64Audio = Base64.getEncoder().encodeToString(audio);
        log.info("Sending audio to frontend - WAV size: {} bytes, Base64 length: {}",
                audio.length, base64Audio.length());
        sendMessage(session, toJson(Map.of(
                "type", "audio",
                "data", base64Audio,
                "text", text
        )));
    }

    /** 流式推送文本消息 */
    private void sendTextMessage(WebSocketSession session, String text) {
        sendTextMessage(session, text, false);
    }

    /**
     * 推送文本消息：isFinal=false 为流式增量（前端原地刷新），true 为定稿（前端换行锁定）。
     */
    private void sendTextMessage(WebSocketSession session, String text, boolean isFinal) {
        sendMessage(session, toJson(Map.of(
                "type", "text",
                "content", text,
                "final", isFinal
        )));
    }

    /**
     * 推送错误消息到 WebSocketSession session。
     */
    private void sendError(WebSocketSession session, String error) {
        sendMessage(session, toJson(Map.of("type", "error", "message", error)));
    }

    /**
     * ASR 就绪状态通知（前端解锁「开始说话」交互）。
     */
    private void sendAsrReady(WebSocketSession session) {
        sendAsrStatus(session, "asr_ready", "语音识别已就绪");
    }

    /**
     * 将 ASR 状态发送给 WebSocket 会话（ready/reconnecting 等）。
     */
    private void sendAsrStatus(WebSocketSession session, String action, String message) {
        if (session == null || !session.isOpen()) {
            return;
        }
        sendMessage(session, toJson(Map.of(
                "type", "control",
                "action", action,
                "message", message,
                "timestamp", System.currentTimeMillis()
        )));
    }

    /**
     * 推送单块音频（流式播放）：前端按 index 顺序排队播放，
     * isLast 标记最后一块，用于结束播放状态。
     */
    private void sendAudioChunk(WebSocketSession session, byte[] wavAudio, int index, boolean isLast) {
        if (!session.isOpen()) {
            return;
        }
        String base64Audio = Base64.getEncoder().encodeToString(wavAudio);
        sendMessage(session, toJson(Map.of(
                "type", "audio_chunk",
                "data", base64Audio,
                "index", index,
                "isLast", isLast
        )));
        log.debug("[Session] Sent audio chunk index={}, isLast={}, size={} bytes", index, isLast, wavAudio.length);
    }

    /**
     * 通知前端「面试官语音播放完成」（前端可重新开启录音提示）。
     */
    private void sendAudioComplete(WebSocketSession session) {
        if (session == null || !session.isOpen()) {
            return;
        }
        sendMessage(session, toJson(Map.of(
                "type", "control",
                "action", "audio_complete",
                "message", "面试官语音播放完成",
                "timestamp", System.currentTimeMillis()
        )));
    }

    /**
     * 有序 TTS 分块发射器：句子级 TTS 并发合成，但按句子顺序推送给前端。
     *
     * <h3>为什么需要「有序」</h3>
     * 句子 A、B、C 并发提交 TTS，完成顺序不可控（长句可能比短句慢）。
     * 前端按 index 顺序排队播放，因此必须按提交顺序（index 递增）推送——
     * 乱序推送给前端会导致播放顺序错乱。
     *
     * <h3>工作机制</h3>
     * <ul>
     *   <li>{@code submit}：为句子分配递增 index，异步提交合成，future 存入 map</li>
     *   <li>构造时启动一个 drain 虚拟线程：按 index 顺序逐个取 future，
     *       用 wait/notify 等待「下一个 future 已就绪」，取到即推送并游标 +1</li>
     *   <li>{@code finish}：记录总块数 totalChunks，drain 线程在「索引越过
     *       总块数且无 future」时判定结束（不依赖等待超时）</li>
     *   <li>{@code awaitCompletion}：调用方限时等待 drain 结束（超时上限按
     *       句子数线性放大：每句 ttsTimeout+1 秒），超时则取消并返回已推送块数</li>
     * </ul>
     *
     * <h3>内存考量</h3>
     * 每推完一块就从 futures map 移除——已推送的音频不再驻留内存，
     * 长回复也不会积累全部 PCM。
     */
    private class OrderedTtsChunkEmitter {

        private final String sessionId;
        private final WebSocketSession session;
        private final Semaphore ttsSemaphore;
        private final long ttsTimeoutSec;
        /** index → 该句的合成 future（完成推送后移除） */
        private final Map<Integer, CompletableFuture<byte[]>> futures = new ConcurrentHashMap<>();
        /** 下一个待分配/待推送的索引 */
        private final AtomicInteger nextIndex = new AtomicInteger();
        /** 已成功推送给前端的块数（返回值给调用方做兜底判断） */
        private final AtomicInteger emittedChunks = new AtomicInteger();
        /** drain 线程的等待/唤醒锁 */
        private final Object lock = new Object();
        /** drain 完成信号（值为已推送块数） */
        private final CompletableFuture<Integer> completion;
        /** 总块数；-1 = finish 尚未调用 */
        private volatile int totalChunks = -1;

        OrderedTtsChunkEmitter(
                String sessionId,
                WebSocketSession session,
                Semaphore ttsSemaphore,
                long ttsTimeoutSec) {
            this.sessionId = sessionId;
            this.session = session;
            this.ttsSemaphore = ttsSemaphore;
            this.ttsTimeoutSec = ttsTimeoutSec;
            // drain 线程与发射器同生命周期：构造即启动，等待第一个 future 到达
            this.completion = CompletableFuture.supplyAsync(this::drainChunks, voicePipelineExecutor);
        }

        /**
         * 提交一个句子：分配索引 → 占用信号量 → 异步合成 → 注册 future → 唤醒 drain。
         */
        void submit(String sentence) {
            int index = nextIndex.getAndIncrement();
            ttsSemaphore.acquireUninterruptibly();
            CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return ttsService.synthesize(sentence);
                } finally {
                    ttsSemaphore.release();
                }
            }, voicePipelineExecutor);

            futures.put(index, future);
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        /**
         * 标记提交结束（记录总块数）并唤醒 drain 做终判。
         */
        void finish() {
            synchronized (lock) {
                totalChunks = nextIndex.get();
                lock.notifyAll();
            }
        }

        /**
         * 限时等待 drain 完成。
         *
         * <p>超时上限 = max(ttsTimeout+2, (ttsTimeout+1) × 句子数)：
         * 最坏情况每句都在超时边缘完成，总耗时线性于句子数；
         * 超时后取消 drain、若已有块推过则补发 audio_complete（前端别一直等）。
         */
        int awaitCompletion() {
            long timeoutSec = Math.max(ttsTimeoutSec + 2, (ttsTimeoutSec + 1) * Math.max(1, nextIndex.get()));
            try {
                return completion.get(timeoutSec, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[Session: {}] Streaming TTS chunk emitter did not finish cleanly: {}",
                        sessionId, e.getMessage());
                completion.cancel(true);
                int emitted = emittedChunks.get();
                if (emitted > 0) {
                    sendAudioComplete(session);
                }
                return emitted;
            }
        }

        /**
         * drain 主循环：按索引顺序等待并推送音频块，直到所有块处理完。
         */
        private int drainChunks() {
            int index = 0;
            try {
                while (true) {
                    CompletableFuture<byte[]> future = waitForFuture(index);
                    if (future == null) {
                        int emitted = emittedChunks.get();
                        if (emitted > 0) {  // finish 已调用且索引越过总块数：正常结束
                            sendAudioComplete(session);
                        }
                        return emitted;
                    }

                    try {
                        // 单句超时：超时/异常跳过该句（cancel 释放资源），继续下一句
                        byte[] pcm = future.get(ttsTimeoutSec, TimeUnit.SECONDS);
                        if (pcm != null && pcm.length > 0 && session.isOpen()) {
                            sendAudioChunk(session, convertPcmToWav(pcm), index, false);
                            emittedChunks.incrementAndGet();
                        }
                    } catch (Exception e) {
                        future.cancel(true);
                        log.warn("[Session: {}] Streaming TTS chunk {} failed", sessionId, index, e);
                    } finally {
                        futures.remove(index);
                        index++;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Session: {}] Streaming TTS chunk emitter interrupted", sessionId);
                int emitted = emittedChunks.get();
                if (emitted > 0) {
                    sendAudioComplete(session);
                }
                return emitted;
            }
        }

        /**
         * 等待索引 index 的 future 出现（未出现时 wait，100ms 唤醒一次，防错过通知）。
         * 返回 null 表示「finish 已调用且该索引不存在」——drain 应结束。
         */
        private CompletableFuture<byte[]> waitForFuture(int index) throws InterruptedException {
            synchronized (lock) {
                while (!futures.containsKey(index)) {
                    if (totalChunks >= 0 && index >= totalChunks) {
                        return null;
                    }
                    lock.wait(100);
                }
                return futures.get(index);
            }
        }
    }

    /**
     * 记录纳秒级耗时指标（从 startNanos 到现在）。
     */
    private void recordTimerSinceNanos(String metricName, long startNanos, String... tags) {
        MeterRegistry registry = getRegistry();
        if (registry == null) {
            return;
        }
        long elapsed = Math.max(0, System.nanoTime() - startNanos);
        registry.timer(metricName, tags).record(elapsed, TimeUnit.NANOSECONDS);
    }

    /**
     * 记录毫秒级耗时指标（直接给毫秒值）。
     */
    private void recordTimerMillis(String metricName, long millis, String... tags) {
        MeterRegistry registry = getRegistry();
        if (registry == null) {
            return;
        }
        registry.timer(metricName, tags).record(Math.max(0, millis), TimeUnit.MILLISECONDS);
    }

    /**
     * 计数器 +1（事件类指标）。
     */
    private void incrementCounter(String metricName, String... tags) {
        MeterRegistry registry = getRegistry();
        if (registry == null) {
            return;
        }
        registry.counter(metricName, tags).increment();
    }

    /**
     * 获取 MeterRegistry。用 ObjectProvider 的原因是：MeterRegistry 是可选 Bean
     * （未引入 actuator 时不存在），getIfAvailable 优雅降级为不埋点。
     */
    private MeterRegistry getRegistry() {
        return meterRegistryProvider.getIfAvailable();
    }

    /**
     * 定时任务：检查会话暂停超时（每 30 秒扫一遍所有在线会话）。
     *
     * <p>两级阈值设计：4:30 警告（给用户 30 秒反应时间），
     * 5:00 直接暂停断连——警告先行避免「正答到一半被强停」的糟糕体验。
     */
    @Scheduled(fixedRate = 30000)
    public void checkPauseTimeout() {
        long now = System.currentTimeMillis();

        lastActivityTime.forEach((sessionId, lastTime) -> {
            long elapsed = now - lastTime;

            // Send warning at 4:30
            if (elapsed > WARNING_TIME_MS && elapsed < PAUSE_TIMEOUT_MS) {
                sendPauseWarning(sessionId);
            }
            // Timeout at 5:00
            else if (elapsed >= PAUSE_TIMEOUT_MS) {
                log.warn("Session {} inactive for {} minutes, pausing",
                        sessionId, PAUSE_TIMEOUT_MS / 60000);
                handlePauseTimeout(sessionId);
            }
        });
    }

    /**
     * 每分钟定时清理一次僵尸会话与卡住的评估任务。
     */
    @Scheduled(fixedRate = 60_000)
    public void cleanupStaleSessions() {
        try {
            int cleaned = interviewService.cleanupStaleSessions();
            if (cleaned > 0) {
                log.info("Stale session cleanup: {} sessions cleaned", cleaned);
            }
        } catch (Exception e) {
            log.error("Error during stale session cleanup", e);
        }
    }

    /**
     * Send pause warning notification
     * 发送暂停警告通知
     */
    private void sendPauseWarning(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);
        if (session != null && session.isOpen()) {
            sendMessage(session, toJson(Map.of(
                    "type", "control",
                    "action", "pause_timeout_warning",
                    "message", "会话将在30秒后暂停，请继续说话或点击继续",
                    "timestamp", System.currentTimeMillis()
            )));
        }
    }

    /**
     * Handle pause timeout - save state and disconnect
     * 处理暂停超时 - 保存状态并断开连接。
     *
     * <p>清理顺序：通知前端 → 落库 PAUSED → 关闭连接 → 停 ASR → 移除内存态。
     * 落库在断连之前：会话恢复的依据是数据库状态，先落库保证「断连后一定可恢复」。
     */
    private void handlePauseTimeout(String sessionId) {
        WebSocketSession session = sessions.get(sessionId);

        try {
            if (session != null && session.isOpen()) {
                sendMessage(session, toJson(Map.of(
                        "type", "control",
                        "action", "pause_timeout",
                        "message", "会话因超时已暂停,可在历史记录中恢复",
                        "timestamp", System.currentTimeMillis()
                )));
            }

            // 2. Save session state to database
            interviewService.pauseSession(sessionId, "timeout");

            // 3. Close WebSocket connection
            if (session != null && session.isOpen()) {
                session.close(CloseStatus.GOING_AWAY);
            }

            // 4. Cleanup - Stop ASR session to prevent resource leak
            sttService.stopTranscription(sessionId);
            sessions.remove(sessionId);
            sessionStates.remove(sessionId);
            lastActivityTime.remove(sessionId);

            log.info("Session {} paused due to timeout", sessionId);

        } catch (Exception e) {
            log.error("Error handling pause timeout for session {}", sessionId, e);
        }
    }

    /**
     * Extract session ID from WebSocket URI path, Path format: /ws/voice-interview/{sessionId}
     *  <p>
     * 从 URL 路径提取会话ID（取最后一个 "/" 之后的段）。
     */
    private String extractSessionId(WebSocketSession session) {
        String path = session.getUri().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Get chat history for session.
     * 加载对话历史并进行上下文压缩（滑动窗口 + 可选增量摘要），再格式化为字符串。
     * 当 contextCompression.enabled=false 时行为与改前完全一致（返回全量格式化轮次）。
     *
     * <h3>摘要行的读-压-写流程</h3>
     * <ol>
     *   <li>读对话轮次 + 已持久化的 SUMMARY 行（cachedSummary / coveredTurns）</li>
     *   <li>交给压缩器做增量压缩（按批次阈值决定是否调 LLM 摘要）</li>
     *   <li>若摘要变化：持久化 SUMMARY 行（失败仅告警——本轮对话不受影响）</li>
     * </ol>
     */
    private List<String> getHistory(String sessionId, String llmProvider) {
        try {
            List<VoiceInterviewMessageEntity> turns = interviewService.getConversationHistory(sessionId);
            VoiceInterviewMessageEntity summaryRow = interviewService.loadSummaryRow(sessionId).orElse(null);

            String cachedSummary = summaryRow != null
                    ? VoiceInterviewMessageEntity.trimToNull(summaryRow.getAiGeneratedText()) : null;
            int coveredTurns = (summaryRow != null && summaryRow.getSequenceNum() != null)
                    ? Math.max(0, -summaryRow.getSequenceNum() - 1) : 0;

            var compressed = voiceContextCompressor.compress(
                    turns, cachedSummary, coveredTurns, llmProvider);

            List<String> history = new ArrayList<>();
            if (compressed.summary() != null && !compressed.summary().isBlank()) {
                history.add("【对话摘要】" + compressed.summary());
            }
            history.addAll(voiceContextCompressor.formatRecent(compressed.recent()));

            // 摘要发生变化则持久化（UPSERT），保证断线重连后不重复生成
            if (compressed.changed() && compressed.summary() != null && !compressed.summary().isBlank()) {
                try {
                    interviewService.saveSummaryRow(sessionId, compressed.summary(), compressed.coveredTurns());
                } catch (Exception e) {
                    log.warn("持久化上下文摘要失败（不影响本次应答），session {}", sessionId, e);
                }
            }

            log.debug("Loaded {} compressed history entries for session {}", history.size(), sessionId);
            return history;
        } catch (Exception e) {
            log.error("Error loading conversation history for session {}", sessionId, e);
            return new ArrayList<>();
        }
    }

    /**
     * Get session entity from database
     * 非法格式返回 null（调用方应按「会话不存在」处理）。
     */
    private VoiceInterviewSessionEntity getSessionEntity(String sessionId) {
        try {
            Long sessionIdLong = Long.parseLong(sessionId);
            return interviewService.getSession(sessionIdLong);
        } catch (NumberFormatException e) {
            log.error("Invalid session ID format: {}", sessionId);
            return null;
        }
    }

    /**
     * Save message to database
     * 落库失败只记日志：消息持久化是异步补充，不应影响实时对话体验。
     */
    private void saveMessage(String sessionId, String userText, String aiText) {
        try {
            interviewService.saveMessage(sessionId, userText, aiText);
            log.debug("Message saved to database for session: {}", sessionId);
        } catch (Exception e) {
            log.error("Error saving message for session {}", sessionId, e);
        }
    }

    /**
     * Convert PCM audio to WAV format
     * PCM → WAV：手动拼 44 字节 WAV 头（RIFF + fmt + data 块）。
     * <p>
     * <b>注意</b>：采样率硬编码 24000Hz，与 QwenTtsService 的输出格式
     * （PCM_24000HZ_MONO_16BIT）严格对应——改 TTS 格式必须同步改这里，
     * 否则浏览器播放速度/音调会错误。
     *
     * @param pcmData Raw PCM audio data (24kHz, 16-bit, mono)
     * @return WAV formatted audio data
     */
    private byte[] convertPcmToWav(byte[] pcmData) {
        // Use 24000Hz for Qwen TTS Realtime API
        int sampleRate = 24000;
        int bitsPerSample = 16;
        int numChannels = 1;
        int byteRate = sampleRate * numChannels * bitsPerSample / 8;
        int blockAlign = numChannels * bitsPerSample / 8;
        int dataSize = pcmData.length;
        int fileSize = dataSize + 36;

        byte[] wavData = new byte[dataSize + 44];

        // 直接字节数组写入，避免 ByteArrayOutputStream 的装箱开销
        int pos = 0;

        // RIFF header
        wavData[pos++] = 'R'; wavData[pos++] = 'I'; wavData[pos++] = 'F'; wavData[pos++] = 'F';
        writeIntLE(wavData, pos, fileSize); pos += 4;
        wavData[pos++] = 'W'; wavData[pos++] = 'A'; wavData[pos++] = 'V'; wavData[pos++] = 'E';

        // fmt chunk
        wavData[pos++] = 'f'; wavData[pos++] = 'm'; wavData[pos++] = 't'; wavData[pos++] = ' ';
        writeIntLE(wavData, pos, 16); pos += 4; // Chunk size
        writeShortLE(wavData, pos, (short) 1); pos += 2; // Audio format (1 = PCM)
        writeShortLE(wavData, pos, (short) numChannels); pos += 2;
        writeIntLE(wavData, pos, sampleRate); pos += 4;
        writeIntLE(wavData, pos, byteRate); pos += 4;
        writeShortLE(wavData, pos, (short) blockAlign); pos += 2;
        writeShortLE(wavData, pos, (short) bitsPerSample); pos += 2;

        // data chunk
        wavData[pos++] = 'd'; wavData[pos++] = 'a'; wavData[pos++] = 't'; wavData[pos++] = 'a';
        writeIntLE(wavData, pos, dataSize); pos += 4;

        // Copy PCM data
        System.arraycopy(pcmData, 0, wavData, 44, pcmData.length);

        return wavData;
    }

    /**
     * 小端写 4 字节整数（WAV 规范要求 little-endian）。
     */
    private static void writeIntLE(byte[] buf, int pos, int value) {
        buf[pos] = (byte) (value & 0xFF);
        buf[pos + 1] = (byte) ((value >> 8) & 0xFF);
        buf[pos + 2] = (byte) ((value >> 16) & 0xFF);
        buf[pos + 3] = (byte) ((value >> 24) & 0xFF);
    }

    /**
     * 小端写 2 字节整数。
     */
    private static void writeShortLE(byte[] buf, int pos, short value) {
        buf[pos] = (byte) (value & 0xFF);
        buf[pos + 1] = (byte) ((value >> 8) & 0xFF);
    }

    /**
     * 会话运行时状态（纯内存态，随 WebSocket 生命周期创建/销毁）。
     *
     * <h3>各字段的并发语义</h3>
     * 全部使用原子类型：STT 回调线程、WebSocket 消息线程、虚拟线程管线
     * 三方并发访问，普通字段会有可见性与竞态问题。
     *
     * <h3>mergeBuffer 的职责</h3>
     * 服务端 VAD 会把用户的一段话切成多个 completed 段
     * （说话中的停顿超过 1 秒即切段）。mergeBuffer 负责：
     * <ul>
     *   <li>累积拼接多个段（appendFinalSttSegment）</li>
     *   <li>智能拼接（joinSegments）：去重、防重复前缀、标点衔接</li>
     *   <li>实时字幕预览（confirmed + partial 拼接）</li>
     *   <li>提交时整体取出并清空（takeMergeBufferAndClear）</li>
     * </ul>
     */
    private static class SessionState {
        private final AtomicReference<String> accumulatedText = new AtomicReference<>("");
        private final AtomicBoolean processing = new AtomicBoolean(false);
        /** AI 正在播放 TTS 音频，期间丢弃麦克风回声 */
        private final AtomicBoolean aiSpeaking = new AtomicBoolean(false);
        /** AI 音频播放结束后，额外等待这段时间再接受用户音频（ms），防止回声尾音 */
        private final AtomicLong aiSpeakEndAt = new AtomicLong(0);
        /** 多段 STT completed 拼接，防抖后再送 LLM */
        private final AtomicReference<String> mergeBuffer = new AtomicReference<>("");
        /** mergeBuffer 开始计时点，用于”最长等待补充”判定 */
        private final AtomicLong mergeStartedAt = new AtomicLong(0);
        /** 最近一次 STT 活动时间（partial/final） */
        private final AtomicLong lastSttActivityAt = new AtomicLong(System.currentTimeMillis());
        /** 当前正在执行 LLM+TTS 管线的虚拟线程，断连时可中断 */
        private volatile Thread processingThread = null;

        /**
         * 追加一段 STT 定稿文本到合并缓冲。
         * <ul>
         *   <li>缓冲为空时：直接放入并记录当前时间作为开始计时点</li>
         *   <li>非空时：走 joinSegments 智能拼接（处理 VAD 切段的重叠/重复）</li>
         * </ul>
         */
        void appendFinalSttSegment(String segment) {
            String s = segment == null ? "" : segment.trim();
            if (s.isEmpty()) {
                return;
            }
            mergeBuffer.updateAndGet(prev -> {
                if (prev == null || prev.isEmpty()) {
                    mergeStartedAt.set(System.currentTimeMillis());
                    return s;
                }
                return joinSegments(prev, s);
            });
            markSttActivity();
        }

        /**
         * 两段文本的拼接规则（VAD 切段的重叠修正）：
         * <ol>
         *   <li>新段与前段相同 / 以前段开头：新段包含前段（重叠），取新段</li>
         *   <li>前段以新段结尾：前段包含新段（重叠），取前段</li>
         *   <li>前段以句末标点结尾：空格拼接（新句）</li>
         *   <li>其余：逗号拼接（VAD 把一句话切成两半，语义上仍是同句）</li>
         * </ol>
         */
        private static String joinSegments(String previous, String next) {
            String trimmedPrevious = previous.trim();
            String trimmedNext = next.trim();
            if (trimmedNext.equals(trimmedPrevious) || trimmedNext.startsWith(trimmedPrevious)) {
                return trimmedNext;
            }
            if (trimmedPrevious.endsWith(trimmedNext)) {
                return trimmedPrevious;
            }
            if (trimmedPrevious.endsWith("。") || trimmedPrevious.endsWith("！")
                    || trimmedPrevious.endsWith("？") || trimmedPrevious.endsWith(".")
                    || trimmedPrevious.endsWith("!") || trimmedPrevious.endsWith("?")) {
                return trimmedPrevious + " " + trimmedNext;
            }
            return trimmedPrevious + "，" + trimmedNext;
        }

        /**
         * 当前合并缓冲的用户语音快照（实时字幕用）。
         */
        String getMergeBufferPreview() {
            String s = mergeBuffer.get();
            return s == null ? "" : s;
        }

        /**
         * 直接覆盖合并缓冲（前端 submit 带手输文本时用）。
         * 不追加而是覆盖：手输文本是用户对「识别错误」的最终修正，
         * 与 ASR 累积结果不应混排。
         */
        void setMergeBufferDirectly(String text) {
            String s = text == null ? "" : text.trim();
            if (s.isEmpty()) {
                return;
            }
            mergeBuffer.set(s);
            if (mergeStartedAt.get() == 0) {
                mergeStartedAt.set(System.currentTimeMillis());
            }
        }

        /**
         * 字幕预览：已确认段（mergeBuffer）+ 实时 partial 的拼接。
         * partial 为空时退化为纯确认段快照。
         */
        String getMergeBufferPreviewWithPartial(String partial) {
            String current = partial == null ? "" : partial.trim();
            if (current.isEmpty()) {
                return getMergeBufferPreview();
            }

            String confirmed = getMergeBufferPreview();
            if (confirmed.isBlank()) {
                return current;
            }
            return joinSegments(confirmed, current);
        }

        /**
         * 取出合并缓冲并清空（提交动作，计时点一并归零）。
         */
        String takeMergeBufferAndClear() {
            mergeStartedAt.set(0);
            return mergeBuffer.getAndSet("");
        }

        /**
         * 记录最近 STT 活动时间。
         */
        void markSttActivity() {
            lastSttActivityAt.set(System.currentTimeMillis());
        }

        /**
         * 合并缓冲的起始计时点（未开始时返回当前时间）。
         */
        long getMergeStartedAt() {
            long value = mergeStartedAt.get();
            return value > 0 ? value : System.currentTimeMillis();
        }

        long getLastSttActivityAt() {
            return lastSttActivityAt.get();
        }

        String getAccumulatedText() {
            return accumulatedText.get();
        }

        void setAccumulatedText(String text) {
            accumulatedText.set(text);
        }

        AtomicBoolean isProcessing() {
            return processing;
        }

        void setProcessingThread(Thread t) {
            this.processingThread = t;
        }

        public Thread getProcessingThread() {
            return processingThread;
        }

        /**
         * 是否处于「AI 说话或回声冷却期」——麦克风输入应被丢弃的时间窗口。
         * 冷却期由触发轮 finally 块设置（AI_SPEAK_COOLDOWN_MS=800ms）。
         * @return true 处于冷却期
         */
        boolean isAiSpeakingOrCooldown() {
            if (aiSpeaking.get()) {
                return true;
            }
            // AI 播放结束后的冷却期（默认 800ms），防止扬声器尾音被录入
            return System.currentTimeMillis() < aiSpeakEndAt.get();
        }
    }
}

