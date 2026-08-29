package com.ywy.interviewagentapplication.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality;
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam;
import com.alibaba.dashscope.audio.omni.OmniRealtimeTranscriptionParam;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ywy.interviewagentapplication.modules.voiceinterview.config.VoiceInterviewProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Qwen3 Realtime ASR Service
 * 通义千问实时语音识别（ASR）服务。
 *
 * <p>基于阿里云 DashScope 的 qwen3-asr-flash-realtime 模型，为多个并发面试会话
 * 管理各自的 WebSocket 连接，并利用<b>服务端 VAD（Voice Activity Detection）</b>
 * 自动切分句子。
 *
 * <h3>核心设计</h3>
 * <ol>
 *   <li><b>每会话一条 WebSocket 长连接</b>：ASR 按 sessionId 管理连接生命周期
 *       （start/restart/stop），音频块经 Base64 编码后 appendAudio 推流</li>
 *   <li><b>就绪闩（CountDownLatch）</b>：连接建立是异步的（独立线程 connect + updateSession），
 *       sendAudio 前必须等 markReady；未就绪的音频块在 WebSocket 层被丢弃或重试</li>
 *   <li><b>会话级互斥锁</b>：stop/start 并发调用会破坏连接状态机，
 *       sessionLocks 保证同一会话的 start/stop/restart 串行执行</li>
 *   <li><b>断线重连的身份保护</b>：onClose 回调只移除「与本次连接相同的会话」，
 *       防止旧连接的迟到关闭事件误删重连后的新连接（见 onClose 内注释）</li>
 * </ol>
 *
 * <h3>回调协议</h3>
 * <ul>
 *   <li><b>onFinal</b>：一句定稿（input_audio_transcription.completed），送 LLM 管线</li>
 *   <li><b>onPartial</b>：中间识别结果（text/delta 事件），实时字幕用</li>
 *   <li><b>onReady</b>：连接 + 会话配置完成，通知前端「语音识别已就绪」</li>
 *   <li><b>onError</b>：致命错误（认证失败、连接失败等）</li>
 * </ul>
 *
 * <p>音频格式：PCM、16kHz、16bit、单声道；语言：中文（zh）；VAD：server_vad、静音 1 秒切句。
 *
 * @see OmniRealtimeConversation
 * @see OmniRealtimeCallback
 */
@Slf4j
@Service
public class QwenAsrService {

    /**
     * 运行时配置（构造时从 VoiceInterviewProperties 加载；保留 setter 供测试使用）
     */
    private String url;

    private String model;

    private String apiKey;

    private String language;

    private String format;

    private Integer sampleRate;

    private Boolean enableTurnDetection;

    private String turnDetectionType;

    private Float turnDetectionThreshold;

    private Integer turnDetectionSilenceDurationMs;

    public QwenAsrService(VoiceInterviewProperties voiceInterviewProperties) {
        applyAsrConfig(voiceInterviewProperties.getQwen().getAsr());
    }

    /**
     * 重新加载配置（配置类刷新后调用）。
     */
    public void reload(VoiceInterviewProperties voiceInterviewProperties) {
        applyAsrConfig(voiceInterviewProperties.getQwen().getAsr());
        log.info("QwenAsrService reloaded: model={}, url={}", model, url);
    }

    /**
     * 将配置对象逐字段拷贝到运行时字段。
     * 逐字段拷贝（而非持有配置对象引用）隔离了「配置源可变」的风险，
     * 也保留了字段级 setter 的测试入口。
     */
    private void applyAsrConfig(VoiceInterviewProperties.AsrConfig asr) {
        this.url = asr.getUrl();
        this.model = asr.getModel();
        this.apiKey = asr.getApiKey();
        this.language = asr.getLanguage();
        this.format = asr.getFormat();
        this.sampleRate = asr.getSampleRate();
        this.enableTurnDetection = asr.isEnableTurnDetection();
        this.turnDetectionType = asr.getTurnDetectionType();
        this.turnDetectionThreshold = asr.getTurnDetectionThreshold();
        this.turnDetectionSilenceDurationMs = asr.getTurnDetectionSilenceDurationMs();
    }

    /**
     * 活跃 ASR 会话表：key=面试会话ID，value=连接句柄与回调的封装（AsrSession）。
     * ConcurrentHashMap 支撑多会话并发读写（每会话互不干扰）。
     */
    private final Map<String, AsrSession> sessions = new ConcurrentHashMap<>();

    /** 防止同一 interview sessionId 上并发 stop/start；并在重连时保证同一会话只有一个连接 */
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    /**
     * 获取（或创建）指定会话的锁对象。computeIfAbsent 保证同一会话永远拿到同一把锁。
     */
    private Object lockForSession(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, k -> new Object());
    }

    /**
     * 启动校验：apiKey 缺失时直接抛异常让应用启动失败，保证 Spring 启动即失败而非首次调用才失败。
     * 语音识别是整个产品的主链路，带病启动（每次识别都 401）比启动失败更难排查。
     *
     * @throws IllegalStateException if apiKey is not configured
     */
    @PostConstruct
    public void init() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("API key must be configured before initializing QwenAsrService");
        }
        log.info("QwenAsrService initialized with model: {}, url: {}", model, url);
    }

    /**
     * 开始一个识别会话（无 partial 回调的简写入口）。
     *
     * @param sessionId Unique identifier for this session
     * @param onFinal Callback when a sentence/segment is finalized ({@code completed} event)
     * @param onError Callback invoked when errors occur
     * @throws IllegalStateException if session already exists or service not initialized
     */
    public void startTranscription(String sessionId, Consumer<String> onFinal, Consumer<Throwable> onError) {
        startTranscription(sessionId, onFinal, null, onError);
    }

    /**
     * Same as {@link #startTranscription(String, Consumer, Consumer)} but forwards partial transcripts
     * ({@code conversation.item.input_audio_transcription.text}) for live subtitles.
     * 开始一个识别会话，带 partial（实时字幕）回调的入口。
     *
     * @param onPartial May be null if partials are not needed
     */
    public void startTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        startTranscription(sessionId, onFinal, onPartial, null, onError);
    }

    /**
     * 开启识别会话的完整入口：在会话锁内串行启动。
     *
     * @param onReady 连接+会话配置完成回调（前端收到「识别就绪」提示）
     */
    public void startTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Runnable onReady,
            Consumer<Throwable> onError) {
        synchronized (lockForSession(sessionId)) {
            startTranscriptionLocked(sessionId, onFinal, onPartial, onReady, onError);
        }
    }

    /**
     * 停止旧连接并重新建立（用于 ASR WebSocket 被服务端关闭后恢复识别）。
     *
     * <p>重连在会话锁内完成 stop + start 两个动作，二者之间 sleep 200ms：
     * 给旧连接的关闭握手留出时间，避免新旧连接在 DashScope 侧「撞会话」。
     */
    public void restartTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        restartTranscription(sessionId, onFinal, onPartial, null, onError);
    }

    /**
     * 完整重连入口：stop → 等待 200ms → start → 轮询验证就绪。
     *
     * <h3>为什么重连后还要「验证」</h3>
     * startTranscriptionLocked 的连接是异步的：方法返回不代表连接已可用。
     * 这里最多轮询 10 次 × 100ms，确认新会话 isReady 才算重连成功——
     * 否则 WebSocket 层紧接着推音频块又会立刻失败，形成「重连-失败」死循环。
     */
    public void restartTranscription(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Runnable onReady,
            Consumer<Throwable> onError) {
        synchronized (lockForSession(sessionId)) {
            log.info("[Session: {}] Restarting DashScope ASR (stop + start)", sessionId);
            stopTranscription(sessionId);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            startTranscriptionLocked(sessionId, onFinal, onPartial, onReady, onError);

            // Verify reconnection succeeded
            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    Thread.sleep(100);
                    AsrSession newSession = sessions.get(sessionId);
                    if (newSession != null && newSession.isReady()) {
                        log.info("[Session: {}] ASR reconnection verified successfully", sessionId);
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("[Session: {}] ASR reconnection verification interrupted", sessionId);
                    return;
                }
            }

            log.warn("[Session: {}] ASR reconnection may not be fully ready after 1 second", sessionId);
        }
    }

    /**
     * 会话锁内的真正启动逻辑（调用方必须已持有锁）。
     *
     * <h3>「先注册后连接」的顺序</h3>
     * sessions.put 在 connect() <b>之前</b>执行：连接线程是异步的，
     * 若等连接成功才注册，期间 sendAudio 会因「无会话」抛异常；
     * 先注册 + 就绪闩的组合让「未就绪」成为一个可等待/可判定的中间态。
     */
    private void startTranscriptionLocked(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Runnable onReady,
            Consumer<Throwable> onError) {
        if (sessions.containsKey(sessionId)) {
            throw new IllegalStateException("Session already exists: " + sessionId);
        }

        try {
            // Build OmniRealtimeParam with connection settings
            OmniRealtimeParam param = OmniRealtimeParam.builder()
                    .model(model)
                    .url(url)
                    .apikey(apiKey)
                    .build();

            final AtomicReference<OmniRealtimeConversation> conversationRef = new AtomicReference<>();

            // Create callback handler for WebSocket events
            OmniRealtimeCallback callback = new OmniRealtimeCallback() {
                @Override
                public void onOpen() {
                    log.debug("[Session: {}] WebSocket connection established", sessionId);
                }

                @Override
                public void onEvent(JsonObject message) {
                    handleServerEvent(sessionId, message, onFinal, onPartial, onError);
                }

                @Override
                public void onClose(int code, String reason) {
                    OmniRealtimeConversation closed = conversationRef.get();
                    log.warn("[Session: {}] DashScope ASR WebSocket closed - code: {}, reason: {}",
                            sessionId, code, reason);
                    // 仅移除与本次连接对应的会话，避免重连后旧 onClose 误删新连接（典型「第三轮起无声」根因）
                    // 竞态场景：旧连接关闭事件晚到，此时 sessions 里已是重连的新 AsrSession——
                    // 直接 remove 会把新连接误删。按连接实例身份比对（==）精确删除
                    sessions.compute(sessionId, (id, existing) -> {
                        if (existing != null && closed != null && existing.getConversation() == closed) {
                            return null;
                        }
                        return existing;
                    });
                }
            };

            // Create OmniRealtimeConversation instance
            OmniRealtimeConversation conversation = new OmniRealtimeConversation(param, callback);
            conversationRef.set(conversation);
            AsrSession asrSession = new AsrSession(conversation, onFinal, onPartial, onError);

            // Store session in map BEFORE connecting to ensure hasActiveSession() returns true
            sessions.put(sessionId, asrSession);

            // 连接在独立线程中异步执行：不阻塞 WebSocket 消息处理线程
            Thread connectionThread = new Thread(() -> {
                try {
                    conversation.connect();

                    // Configure session with transcription parameters
                    OmniRealtimeTranscriptionParam transcriptionParam = new OmniRealtimeTranscriptionParam();
                    transcriptionParam.setLanguage(language);
                    transcriptionParam.setInputSampleRate(sampleRate);
                    transcriptionParam.setInputAudioFormat(format);

                    OmniRealtimeConfig config = OmniRealtimeConfig.builder()
                            .modalities(Collections.singletonList(OmniRealtimeModality.TEXT))
                            .enableTurnDetection(enableTurnDetection)
                            .turnDetectionType(turnDetectionType)
                            .turnDetectionThreshold(turnDetectionThreshold)
                            .turnDetectionSilenceDurationMs(turnDetectionSilenceDurationMs)
                            .transcriptionConfig(transcriptionParam)
                            .build();

                    // Update session with configuration
                    conversation.updateSession(config);
                    // 连接已就绪，但会话可能已被 stop/重连替换：确认仍是当前会话才标记就绪
                    if (sessions.get(sessionId) != asrSession) {
                        log.debug("[Session: {}] Ignoring stale ASR connection ready callback", sessionId);
                        return;
                    }
                    asrSession.markReady();
                    if (onReady != null) {
                        onReady.run();
                    }

                    log.info("[Session: {}] Transcription session started successfully", sessionId);

                } catch (Exception e) {
                    log.error("[Session: {}] Failed to establish connection", sessionId, e);
                    // 同样的身份比对清理：失败路径也只清理自己
                    sessions.compute(sessionId, (id, existing) -> {
                        if (existing != null && existing.getConversation() == conversation) {
                            return null;
                        }
                        return existing;
                    });
                    onError.accept(e);
                }
            }, "ASR-Connection-" + sessionId);
            connectionThread.setDaemon(true);
            connectionThread.start();

        } catch (Exception e) {
            String errorMsg = "Failed to create transcription session: " + sessionId;
            log.error(errorMsg, e);
            sessions.remove(sessionId);
            onError.accept(new IllegalStateException(errorMsg, e));
            throw new IllegalStateException(errorMsg, e);
        }
    }

    /**
     * 向 ASR 服务推送一段音频（PCM 原始字节）。
     *
     * <h3>异常语义（与 WebSocket 层协定的协议）</h3>
     * <ul>
     *   <li><b>"ASR session not ready"</b>：连接尚未就绪，调用方可丢弃本块（可恢复）</li>
     *   <li><b>"No active session" / "ASR append failed"</b>：连接已死，
     *       调用方应重启连接并重试（见 WebSocketHandler.handleUserAudio）</li>
     * </ul>
     *
     * @param sessionId Session identifier
     * @param audioData Raw PCM audio bytes
     * @throws IllegalStateException if session does not exist
     */
    public void sendAudio(String sessionId, byte[] audioData) {
        AsrSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalStateException("No active session found: " + sessionId);
        }

        try {
            if (!session.awaitReady(1200)) {
                throw new IllegalStateException("ASR session not ready: " + sessionId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ASR session ready wait interrupted: " + sessionId, e);
        }

        try {
            // Convert audio data to Base64
            String audioBase64 = Base64.getEncoder().encodeToString(audioData);

            // Send to ASR service
            session.getConversation().appendAudio(audioBase64);

            log.trace("[Session: {}] Sent {} bytes of audio data", sessionId, audioData.length);

        } catch (Exception e) {
            log.error("[Session: {}] appendAudio failed (upstream may reconnect)", sessionId, e);
            // 抛出以便 WebSocket 层执行 restartTranscription；不在此重复 onError 避免用户先看到红条再恢复
            throw new IllegalStateException("ASR append failed: " + sessionId, e);
        }
    }

    /**
     * 停止识别会话：从会话表移除、优雅结束（endSession）+ 关闭连接。
     * 在会话锁内执行，与 start/restart 互斥。
     *
     * @param sessionId Session identifier
     */
    public void stopTranscription(String sessionId) {
        synchronized (lockForSession(sessionId)) {
            AsrSession session = sessions.remove(sessionId);
            // Clean up the session lock to prevent memory leak
            sessionLocks.remove(sessionId);
            if (session == null) {
                log.warn("[Session: {}] Attempted to stop non-existent session", sessionId);
                return;
            }

            try {
                session.getConversation().endSession();
                log.info("[Session: {}] Transcription session stopped", sessionId);
            } catch (InterruptedException e) {
                log.error("[Session: {}] Thread interrupted while ending session", sessionId, e);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("[Session: {}] Error while ending session (may already be closed): {}", sessionId, e.getMessage());
            }

            try {
                session.getConversation().close();
            } catch (Exception e) {
                log.debug("[Session: {}] Connection already closed: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * 检查指定的会话当前是否存活
     *
     * @param sessionId Session identifier
     * @return true if session exists and is active, false otherwise
     */
    public boolean hasActiveSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    /**
     * 会话是否已就绪（连接建立且 updateSession 完成）。
     */
    public boolean isReady(String sessionId) {
        AsrSession session = sessions.get(sessionId);
        return session != null && session.isReady();
    }

    /**
     * Spring 容器关闭时自动清理所有活跃会话，释放资源。
     */
    @PreDestroy
    public void destroy() {
        log.info("Destroying QwenAsrService with {} active sessions", sessions.size());

        // Stop all active sessions
        sessions.keySet().forEach(sessionId -> {
            try {
                stopTranscription(sessionId);
            } catch (Exception e) {
                log.error("[Session: {}] Error during cleanup", sessionId, e);
            }
        });

        sessions.clear();
        log.info("QwenAsrService destroyed successfully");
    }

    /**
     * 处理 DashScope 服务端推送的事件：
     * <ul>
     *   <li><b>session.created / session.updated</b>：会话生命周期事件（仅日志）</li>
     *   <li><b>input_audio_transcription.completed</b>：一句定稿 → onFinal（送 LLM）</li>
     *   <li><b>input_audio_transcription.text / .delta</b>：中间结果 → onPartial（实时字幕）</li>
     *   <li><b>error</b>：服务端错误 → onError</li>
     *   <li><b>input_audio_transcription.failed</b>：单句识别失败（仅日志，不影响会话——失败一句话好过整场中断）</li>
     * </ul>
     *
     * @param sessionId Session identifier
     * @param message JSON event message from server
     * @param onFinal Callback for finalized segment text
     * @param onPartial Callback for streaming partial text (optional)
     * @param onError Callback for errors
     */
    private void handleServerEvent(
            String sessionId,
            JsonObject message,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Consumer<Throwable> onError) {
        try {
            String eventType = message.get("type").getAsString();

            log.trace("[Session: {}] Received event: {}", sessionId, eventType);

            switch (eventType) {
                case "session.created":
                    log.debug("[Session: {}] Session created on server", sessionId);
                    break;

                case "session.updated":
                    log.debug("[Session: {}] Session configuration updated", sessionId);
                    break;

                case "conversation.item.input_audio_transcription.completed":
                    // 一句话的最终识别结果：VAD 判定的完整句子
                    JsonObject transcriptObj = message.getAsJsonObject();
                    String transcript = transcriptObj.get("transcript").getAsString();
                    String language = transcriptObj.has("language") ?
                            transcriptObj.get("language").getAsString() : "unknown";
                    String emotion = transcriptObj.has("emotion") ?
                            transcriptObj.get("emotion").getAsString() : "neutral";

                    log.debug("[Session: {}] Transcription completed - language: {}, emotion: {}, text: {}",
                            sessionId, language, emotion, transcript);

                    onFinal.accept(transcript);
                    break;

                case "conversation.item.input_audio_transcription.text":
                case "conversation.item.input_audio_transcription.delta":
                    // 中间结果事件：转发给实时字幕回调
                    dispatchPartialTranscript(sessionId, message, onPartial);
                    break;

                case "error":
                    // Error event
                    JsonObject errorObj = message.getAsJsonObject("error");
                    String errorType = errorObj.has("type") ? errorObj.get("type").getAsString() : "unknown";
                    String errorCode = errorObj.has("code") ? errorObj.get("code").getAsString() : "unknown";
                    String errorMessage = errorObj.has("message") ? errorObj.get("message").getAsString() : "Unknown error";

                    String fullErrorMessage = String.format("ASR Error [%s/%s]: %s", errorType, errorCode, errorMessage);
                    log.error("[Session: {}] {}", sessionId, fullErrorMessage);

                    onError.accept(new IllegalStateException(fullErrorMessage));
                    break;

                case "session.finished":
                    log.debug("[Session: {}] Session finished on server", sessionId);
                    break;

                case "conversation.item.input_audio_transcription.failed":
                    log.error("[Session: {}] ASR transcription failed (single utterance): {}", sessionId, message);
                    break;

                default:
                    if (eventType != null && eventType.contains("transcription")) {
                        log.debug("[Session: {}] Unhandled transcription-related event: {}", sessionId, message);
                    } else {
                        log.trace("[Session: {}] Unhandled event type: {}", sessionId, eventType);
                    }
            }

        } catch (Exception e) {
            log.error("[Session: {}] Error processing server event", sessionId, e);
            onError.accept(e);
        }
    }

    /**
     * 转发中间识别结果给实时字幕回调。没有消费者时静默丢弃（trace 日志）。
     */
    private void dispatchPartialTranscript(
            String sessionId, JsonObject message, Consumer<String> onPartial) {
        if (onPartial == null) {
            log.trace("[Session: {}] Partial transcription received (no consumer)", sessionId);
            return;
        }
        String text = extractTranscriptPayload(message);
        if (text != null && !text.isBlank()) {
            onPartial.accept(text);
        } else {
            log.trace("[Session: {}] Partial ASR event without extractable text: {}", sessionId, message);
        }
    }

    /**
     * 从 ASR 事件 JSON 中提取可展示文本。
     *
     * <h3>为什么有这么多分支</h3>
     * 实时 ASR 协议中文本散落在多种形态里：
     * <ul>
     *   <li>completed 事件的顶层 {@code transcript}（定稿整句）</li>
     *   <li>text 事件的 {@code text}（已确认前缀）+ {@code stash}（草稿后缀），
     *       二者拼接才是当前完整中间结果</li>
     *   <li>delta 事件的 {@code delta.text / delta.transcript}（增量片段）</li>
     *   <li>item 包装结构里的 {@code item.transcript}</li>
     * </ul>
     * 逐层兜底解析，保证字幕在协议演进后仍能工作。
     */
    public static String extractTranscriptPayload(JsonObject message) {
        if (message.has("transcript") && !message.get("transcript").isJsonNull()) {
            JsonElement el = message.get("transcript");
            if (el.isJsonPrimitive()) {
                return el.getAsString();
            }
        }
        // Real-time partial: text + stash (see Alibaba qwen-asr-realtime server events doc)
        if (message.has("text") || message.has("stash")) {
            String prefix = "";
            String suffix = "";
            if (message.has("text") && !message.get("text").isJsonNull() && message.get("text").isJsonPrimitive()) {
                prefix = message.get("text").getAsString();
            }
            if (message.has("stash") && !message.get("stash").isJsonNull() && message.get("stash").isJsonPrimitive()) {
                suffix = message.get("stash").getAsString();
            }
            String combined = prefix + suffix;
            if (!combined.isBlank()) {
                return combined;
            }
        }
        if (message.has("delta")) {
            JsonElement d = message.get("delta");
            if (d.isJsonPrimitive()) {
                return d.getAsString();
            }
            if (d.isJsonObject()) {
                JsonObject o = d.getAsJsonObject();
                if (o.has("text") && !o.get("text").isJsonNull()) {
                    return o.get("text").getAsString();
                }
                if (o.has("transcript") && !o.get("transcript").isJsonNull()) {
                    return o.get("transcript").getAsString();
                }
            }
        }
        if (message.has("item") && message.get("item").isJsonObject()) {
            JsonObject item = message.getAsJsonObject("item");
            if (item.has("transcript") && !item.get("transcript").isJsonNull()) {
                return item.get("transcript").getAsString();
            }
        }
        return null;
    }

    /**
     * 会话内部状态：连接句柄 + 回调 + 就绪闩。
     *
     * <p>就绪闩（CountDownLatch）是异步连接与同步推送之间的桥梁：
     * 连接线程 markReady() 放行，sendAudio 侧 awaitReady() 阻塞等待，
     * 带超时保证「永远就绪不了」的连接也能被快速判定失败。
     */
    private static class AsrSession {
        private final OmniRealtimeConversation conversation;
        private final Consumer<String> onFinal;
        private final Consumer<String> onPartial;
        private final Consumer<Throwable> onError;
        private final CountDownLatch readyLatch = new CountDownLatch(1);

        AsrSession(
                OmniRealtimeConversation conversation,
                Consumer<String> onFinal,
                Consumer<String> onPartial,
                Consumer<Throwable> onError) {
            this.conversation = conversation;
            this.onFinal = onFinal;
            this.onPartial = onPartial;
            this.onError = onError;
        }

        public OmniRealtimeConversation getConversation() {
            return conversation;
        }

        public Consumer<Throwable> getOnError() {
            return onError;
        }

        void markReady() {
            readyLatch.countDown();
        }

        boolean isReady() {
            return readyLatch.getCount() == 0;
        }

        boolean awaitReady(long timeoutMs) throws InterruptedException {
            return readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    // 以下 setter 供测试/配置刷新使用

    public void setUrl(String url) {
        this.url = url;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public void setSampleRate(Integer sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void setEnableTurnDetection(Boolean enableTurnDetection) {
        this.enableTurnDetection = enableTurnDetection;
    }

    public void setTurnDetectionType(String turnDetectionType) {
        this.turnDetectionType = turnDetectionType;
    }

    public void setTurnDetectionThreshold(Float turnDetectionThreshold) {
        this.turnDetectionThreshold = turnDetectionThreshold;
    }

    public void setTurnDetectionSilenceDurationMs(Integer turnDetectionSilenceDurationMs) {
        this.turnDetectionSilenceDurationMs = turnDetectionSilenceDurationMs;
    }
}

