package com.ywy.interviewagentapplication.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtime;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeAudioFormat;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeCallback;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeConfig;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeParam;
import com.google.gson.JsonObject;
import com.ywy.interviewagentapplication.modules.voiceinterview.config.VoiceInterviewProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import okhttp3.WebSocket;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通义千问实时语音合成（TTS）服务。
 *
 * <p>基于阿里云 DashScope 的 qwen3-tts-flash-realtime 模型（WebSocket API），
 * 提供<b>同步阻塞式</b>合成接口。
 *
 * <h3>核心设计</h3>
 * <ol>
 *   <li><b>短连接模式</b>：每次合成新建一条 WebSocket，合成完即关——与 ASR 的
 *       长连接相反。理由：TTS 是「一次性任务」，语句之间天然有空档，
 *       短连接避免维护空闲长连接的复杂状态管理</li>
 *   <li><b>回调 + 闩的桥接</b>：SDK 是异步回调风格，业务层要同步拿结果——
 *       用 CountDownLatch 把异步音频块收集（response.audio.delta）桥接为
 *       「等合成完成」的阻塞调用，超时 30 秒防永久阻塞</li>
 *   <li><b>commit 模式</b>：appendText 之后显式 commit 才触发合成，
 *       保证整句文本一次性合成（而非边推边合导致语气断裂）</li>
 *   <li><b>失败返回空数组而非抛异常</b>：TTS 失败不致命（文本已推给前端），
 *       调用方（WebSocketHandler）拿到空数组会执行兜底策略（整文重试/跳过），
 *       异常语义被收敛到「空 = 失败」的简单契约</li>
 * </ol>
 *
 * <p>音频格式：PCM、24kHz、16bit、单声道（注意与 ASR 的 16kHz 不同）。
 *
 * @see QwenTtsRealtime
 * @see QwenTtsRealtimeCallback
 */
@Slf4j
@Service
public class QwenTtsService {

    // 运行时配置（构造时从 VoiceInterviewProperties 加载；保留 setter 供测试使用）
    private String model;

    private String apiKey;

    private String voice;

    private String format;

    private Integer sampleRate;

    private String mode;

    private String languageType;

    private Float speechRate;

    private Integer volume;

    private int connectTimeoutSeconds;

    public QwenTtsService(VoiceInterviewProperties voiceInterviewProperties) {
        applyTtsConfig(voiceInterviewProperties);
    }

    public void reload(VoiceInterviewProperties voiceInterviewProperties) {
        applyTtsConfig(voiceInterviewProperties);
        log.info("QwenTtsService reloaded: model={}, voice={}, connectTimeoutSeconds={}",
                model, voice, connectTimeoutSeconds);
    }

    private void applyTtsConfig(VoiceInterviewProperties voiceInterviewProperties) {
        VoiceInterviewProperties.QwenTtsConfig tts = voiceInterviewProperties.getQwen().getTts();
        this.model = tts.getModel();
        this.apiKey = tts.getApiKey();
        this.voice = tts.getVoice();
        this.format = tts.getFormat();
        this.sampleRate = tts.getSampleRate();
        this.mode = tts.getMode();
        this.languageType = tts.getLanguageType();
        this.speechRate = tts.getSpeechRate();
        this.volume = tts.getVolume();
        // 建连超时取下限 1 秒：配置误填 0 会导致 await 立即超时、永远建不上连接
        this.connectTimeoutSeconds = Math.max(1, voiceInterviewProperties.getTtsConnectTimeoutSeconds());
    }

    /**
     * 启动校验：apiKey 缺失即启动失败（与 ASR 服务同理，主链路不允许带病启动）。
     * <p>
     * This method is automatically called by Spring after the service is constructed
     * and all configuration values have been loaded from VoiceInterviewProperties.
     *
     * @throws IllegalStateException if apiKey is not configured
     */
    @PostConstruct
    public void init() {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalStateException("API key must be configured before initializing QwenTtsService");
        }
        log.info("QwenTtsService initialized with model: {}, voice: {}, sampleRate: {}Hz",
                model, voice, sampleRate);
    }

    /**
     * 同步合成一段文本为 PCM 音频（16bit/24kHz/单声道）。
     *
     * <h3>流程</h3>
     * <ol>
     *   <li>创建 WebSocket 连接（带建连超时）</li>
     *   <li>updateSession 配置音色/格式/语速等</li>
     *   <li>appendText + commit 触发合成</li>
     *   <li>收集 response.audio.delta 的音频块，等待 response.done（30 秒超时）</li>
     *   <li>finally 中关闭连接（无论成功失败都不残留连接）</li>
     * </ol>
     *
     * @param text Text to synthesize (null, empty, or whitespace-only text returns empty array)
     * @return PCM audio data at configured sample rate, or empty array if synthesis fails
     */
    public byte[] synthesize(String text) {
        // Handle null, empty, or whitespace-only text
        if (text == null || text.trim().isEmpty()) {
            log.debug("Empty or null text provided, returning empty audio array");
            return new byte[0];
        }

        log.debug("Starting TTS synthesis for text: {} characters", text.length());

        // Latch for synchronous waiting
        CountDownLatch synthesisLatch = new CountDownLatch(1);

        // Container for collected audio data
        ByteArrayContainer audioContainer = new ByteArrayContainer();

        // Error container
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        // Response ID container for tracking
        AtomicReference<String> responseIdRef = new AtomicReference<>();

        try {
            // Build QwenTtsRealtimeParam with connection settings
            QwenTtsRealtimeParam param = QwenTtsRealtimeParam.builder()
                    .model(model)
                    .apikey(apiKey)
                    .build();

            // Create callback handler for WebSocket events
            QwenTtsRealtimeCallback callback = new QwenTtsRealtimeCallback() {
                @Override
                public void onOpen() {
                    log.debug("TTS WebSocket connection established");
                }

                @Override
                public void onEvent(JsonObject message) {
                    handleServerEvent(message, audioContainer, synthesisLatch, errorRef, responseIdRef);
                }

                @Override
                public void onClose(int code, String reason) {
                    log.debug("TTS WebSocket closed - code: {}, reason: {}", code, reason);
                    // 连接关闭也放行闩：避免 response.done 因连接中断而永远不来，调用线程永久阻塞
                    synthesisLatch.countDown();
                }
            };

            AtomicReference<Throwable> connectionFailureRef = new AtomicReference<>();
            CountDownLatch connectionFinishedLatch = new CountDownLatch(1);
            QwenTtsRealtime qwenTtsRealtime = createClient(
                    param, callback, connectionFailureRef, connectionFinishedLatch);

            try {
                // 带超时建连：SDK 握手可能因网络问题挂起，没有超时会卡死调用线程
                connectWithTimeout(qwenTtsRealtime, connectionFailureRef, connectionFinishedLatch);

                // Configure session with TTS parameters
                QwenTtsRealtimeConfig config = QwenTtsRealtimeConfig.builder()
                        .voice(voice)
                        .responseFormat(getAudioFormat())
                        .mode(mode)  // "commit" mode
                        .languageType(languageType)
                        .speechRate(speechRate)
                        .volume(volume)
                        .build();

                // Update session with configuration
                qwenTtsRealtime.updateSession(config);

                log.info("[TTS] Session configured with voice: {}, triggering synthesis for text (length: {})",
                        voice, text.length());

                // Send text for synthesis using commit mode
                qwenTtsRealtime.appendText(text);
                qwenTtsRealtime.commit();

                log.info("[TTS] Text sent to TTS service, waiting for audio response...");

                // Wait for synthesis completion with timeout
                boolean completed = synthesisLatch.await(30, TimeUnit.SECONDS);

                if (!completed) {
                    log.error("TTS synthesis timeout after 30 seconds");
                    return new byte[0];
                }

                // Check if error occurred
                Throwable error = errorRef.get();
                if (error != null) {
                    log.error("TTS synthesis failed", error);
                    return new byte[0];
                }

                // Return collected audio data
                byte[] audioData = audioContainer.toByteArray();
                log.info("[TTS] Synthesis completed successfully - {} bytes of audio data, responseId: {}",
                        audioData.length, responseIdRef.get());

                return audioData;

            } finally {
                // 无论成败都关闭连接：短连接模式不残留资源
                try {
                    qwenTtsRealtime.close();
                } catch (Exception e) {
                    log.error("Error closing TTS connection", e);
                }
            }

        } catch (InterruptedException e) {
            log.error("TTS synthesis interrupted", e);
            Thread.currentThread().interrupt();
            return new byte[0];
        } catch (Exception e) {
            log.error("Failed to synthesize text", e);
            return new byte[0];
        }
    }

    /**
     * 创建 TTS 客户端并挂钩握手失败回调。
     *
     * <p>SDK 的 connect() 握手失败不抛异常而是走 onFailure 回调，
     * 这里把回调结果转发进 connectionFailureRef + 闩，供 connectWithTimeout 判定。
     */
    private QwenTtsRealtime createClient(
            QwenTtsRealtimeParam param,
            QwenTtsRealtimeCallback callback,
            AtomicReference<Throwable> connectionFailureRef,
            CountDownLatch connectionFinishedLatch) {
        return new QwenTtsRealtime(param, callback) {
            @Override
            public void onFailure(WebSocket webSocket, Throwable throwable, Response response) {
                String status = response == null
                        ? "no HTTP response"
                        : "HTTP " + response.code() + " " + response.message();
                connectionFailureRef.compareAndSet(
                        null,
                        new IllegalStateException("TTS WebSocket handshake failed: " + status, throwable));
                connectionFinishedLatch.countDown();
            }
        };
    }

    /**
     * 带超时建连：在虚拟线程中执行 connect，主线程限时等待。
     *
     * <h3>为什么用虚拟线程 + interrupt 兜底</h3>
     * SDK 的 connect() 是阻塞调用，本身不提供超时参数；把它扔进虚拟线程后：
     * <ul>
     *   <li>超时：主线程抛 TimeoutException，finally 中 interrupt 虚拟线程——
     *       connect 若响应中断则线程终止；不响应则线程自然挂起由 JVM 回收
     *       （虚拟线程开销极小，泄漏代价可接受）</li>
     *   <li>完成：闩放行后检查失败引用，把 SDK 的失败转成异常抛出</li>
     * </ul>
     */
    private void connectWithTimeout(
            QwenTtsRealtime qwenTtsRealtime,
            AtomicReference<Throwable> connectionFailureRef,
            CountDownLatch connectionFinishedLatch) throws Exception {
        Thread connectThread = Thread.ofVirtual()
                .name("qwen-tts-connect")
                .start(() -> {
                    try {
                        qwenTtsRealtime.connect();
                    } catch (Throwable throwable) {
                        connectionFailureRef.compareAndSet(null, throwable);
                    } finally {
                        connectionFinishedLatch.countDown();
                    }
                });

        try {
            boolean completed = connectionFinishedLatch.await(connectTimeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                throw new TimeoutException(
                        "TTS WebSocket connection timed out after " + connectTimeoutSeconds + " seconds");
            }
        } finally {
            if (connectThread.isAlive()) {
                connectThread.interrupt();
            }
        }

        Throwable failure = connectionFailureRef.get();
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure != null) {
            throw new IllegalStateException("TTS WebSocket connection failed", failure);
        }
    }

    /**
     * 音频格式固定为 24kHz/16bit/单声道 PCM——与下游
     * {@code VoiceInterviewWebSocketHandler#convertPcmToWav} 写 WAV 头时的
     * 24000Hz 参数保持一致（此处是事实的唯一来源，改格式必须同步改 WAV 头）。
     *
     * @return QwenTtsRealtimeAudioFormat enum value
     */
    private QwenTtsRealtimeAudioFormat getAudioFormat() {
        // Qwen TTS Realtime uses 24kHz by default
        return QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT;
    }

    /**
     * Spring 容器自动关闭时清理。短连接模式下无持久资源，仅打日志。
     * This method is called automatically when the Spring container shuts down.
     * Currently, no persistent resources need cleanup as each synthesis creates
     * its own temporary connection.
     */
    @PreDestroy
    public void destroy() {
        log.info("QwenTtsService destroyed successfully");
    }

    /**
     * 处理 TTS 服务端事件：
     * <ul>
     *   <li><b>response.audio.delta</b>：音频块（Base64 delta），逐块 append 收集</li>
     *   <li><b>response.done</b>：合成完成（最终事件），放行闩</li>
     *   <li><b>error</b>：记录错误并放行闩（调用方读 errorRef 判定失败）</li>
     *   <li>其他：仅日志</li>
     * </ul>
     *
     * @param message JSON event message from server
     * @param audioContainer Container for collecting audio chunks
     * @param synthesisLatch Latch to signal completion
     * @param errorRef Container for error tracking
     * @param responseIdRef Container for response ID tracking
     */
    private void handleServerEvent(JsonObject message, ByteArrayContainer audioContainer,
                                   CountDownLatch synthesisLatch, AtomicReference<Throwable> errorRef,
                                   AtomicReference<String> responseIdRef) {
        try {
            String eventType = message.get("type").getAsString();

            if (log.isTraceEnabled()) {
                log.trace("Received TTS event: {}, full message: {}", eventType, message);
            } else {
                log.debug("Received TTS event: {}", eventType);
            }

            switch (eventType) {
                case "session.created":
                    String sessionId = message.has("session") && message.get("session").isJsonObject()
                            ? message.get("session").getAsJsonObject().get("id").getAsString()
                            : "unknown";
                    log.debug("TTS session created: {}", sessionId);
                    break;

                case "session.updated":
                    log.debug("TTS session configuration updated");
                    break;

                case "response.audio.delta":
                    // Audio chunk received - delta is a base64 string directly
                    if (message.has("delta")) {
                        String audioBase64 = message.get("delta").getAsString();
                        if (audioBase64 != null && !audioBase64.isEmpty()) {
                            byte[] audioChunk = Base64.getDecoder().decode(audioBase64);
                            audioContainer.append(audioChunk);
                            log.trace("Received audio chunk - {} bytes", audioChunk.length);
                        }
                    }
                    break;

                case "response.done":
                    // Response completed - this is the final event in Qwen TTS API
                    String responseId = responseIdRef.get();
                    log.debug("TTS response completed - responseId: {}", responseId);
                    synthesisLatch.countDown();
                    break;

                case "error":
                    // Error event
                    if (message.has("error")) {
                        var errorElement = message.get("error");
                        String errorType = "unknown";
                        String errorCode = "unknown";
                        String errorMessage = "Unknown error";

                        if (errorElement.isJsonObject()) {
                            JsonObject errorObj = errorElement.getAsJsonObject();
                            errorType = errorObj.has("type") ? errorObj.get("type").getAsString() : "unknown";
                            errorCode = errorObj.has("code") ? errorObj.get("code").getAsString() : "unknown";
                            errorMessage = errorObj.has("message") ? errorObj.get("message").getAsString() : "Unknown error";
                        } else {
                            errorMessage = errorElement.toString();
                        }

                        String fullErrorMessage = String.format("TTS Error [%s/%s]: %s", errorType, errorCode, errorMessage);
                        log.error("{}", fullErrorMessage);

                        errorRef.set(new IllegalStateException(fullErrorMessage));
                        synthesisLatch.countDown();
                    }
                    break;

                default:
                    log.trace("Unhandled TTS event type: {}", eventType);
            }

        } catch (Exception e) {
            log.error("Error processing TTS server event", e);
            errorRef.set(e);
            synthesisLatch.countDown();
        }
    }

    /**
     * 音频块收集容器。
     *
     * <p>用 ByteArrayOutputStream 而非手工数组扩容：内部按需倍增，
     * 均摊 O(1) 追加；手工 System.arraycopy 扩容每次都是 O(n)，长文本
     * 会有几十上百个音频块，O(n²) 的拷贝代价不可忽略。
     * synchronized 防御多线程事件回调并发 append（SDK 回调线程不唯一）。
     */
    private static class ByteArrayContainer {
        private final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

        public synchronized void append(byte[] chunk) {
            baos.write(chunk, 0, chunk.length);
        }

        public synchronized byte[] toByteArray() {
            return baos.toByteArray();
        }
    }

    // 以下 setter 供测试/配置刷新使用

    public void setModel(String model) {
        this.model = model;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setVoice(String voice) {
        this.voice = voice;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public void setSampleRate(Integer sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public void setLanguageType(String languageType) {
        this.languageType = languageType;
    }

    public void setSpeechRate(Float speechRate) {
        this.speechRate = speechRate;
    }

    public void setVolume(Integer volume) {
        this.volume = volume;
    }
}

