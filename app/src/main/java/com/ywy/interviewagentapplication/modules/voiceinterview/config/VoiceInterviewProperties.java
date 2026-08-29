package com.ywy.interviewagentapplication.modules.voiceinterview.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语音面试模块的配置属性类。
 *
 * <h3>配置树结构</h3>
 * 对应 application.yml 中 {@code app.voice-interview.*} 前缀，按职责划分为六个分组：
 * <ul>
 *   <li><b>phase</b> —— 四个面试阶段（INTRO/TECH/PROJECT/HR）的时长与题量阈值，驱动阶段切换决策</li>
 *   <li><b>aliyun</b> —— 阿里云旧版一句话识别/合成 SDK 的凭据（历史兼容保留）</li>
 *   <li><b>rateLimit</b> —— 会话创建频率与并发上限</li>
 *   <li><b>audio</b> —— 前端采集音频的编码参数</li>
 *   <li><b>qwen</b> —— 当前实际使用的通义千问实时 ASR/TTS WebSocket 服务配置</li>
 *   <li><b>opening</b> —— 开场白文案模板（按技能方向区分）</li>
 *   <li><b>contextCompression</b> —— 长会话上下文压缩（滑动窗口 + 滚动摘要）</li>
 * </ul>
 *
 * <h3>设计要点</h3>
 * <ol>
 *   <li>所有阈值均给默认值，保证「零配置可启动」——这也是布尔开关普遍默认 false/关闭的原因：
 *       避免启动时产生不必要的云端调用（如开场白音频预热）。</li>
 * </ol>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.voice-interview")
public class VoiceInterviewProperties {

    private PhaseConfig phase = new PhaseConfig();
    private AliyunConfig aliyun = new AliyunConfig();
    private RateLimitConfig rateLimit = new RateLimitConfig();
    private AudioConfig audio = new AudioConfig();
    private QwenConfig qwen = new QwenConfig();
    private OpeningConfig opening = new OpeningConfig();
    private ContextCompressionConfig contextCompression = new ContextCompressionConfig();

    /**
     * 语音面试单轮面试官回复最大字符数（超出会截断到句子边界）。
     */
    private int aiQuestionMaxChars = 120;
    /**
     * 是否启用 LLM 流式文本下发（先文本后音频），用于降低首字等待时延。
     */
    private boolean llmStreamingEnabled = true;
    /**
     * 流式文本最小推送间隔（毫秒），避免 WebSocket 过于频繁刷屏。
     */
    private int aiStreamPushIntervalMs = 180;
    /**
     * 流式文本推送最小增量字符数。
     */
    private int aiStreamMinCharsDelta = 12;
    /**
     * 每会话允许的并发 TTS 合成调用上限（防止 DashScope 连接限制）。
     */
    private int maxConcurrentTtsPerSession = 3;
    /**
     * 是否启用分块音频推送（每句 TTS 完成后立即推送，不等全部完成）。
     */
    private boolean chunkedAudioEnabled = true;
    /**
     * 单句 TTS 合成超时（秒），超时后跳过该句，用已成功的句子拼合音频。
     */
    private int ttsTimeoutSeconds = 8;
    /**
     * TTS WebSocket 建连超时（秒），避免 SDK 握手失败后永久等待。
     */
    private int ttsConnectTimeoutSeconds = 5;
    /**
     * 是否在应用启动时预热开场白音频缓存。默认关闭，避免启动时产生云端 TTS 调用。
     */
    private boolean openingAudioWarmupEnabled = false;

    /**
     * 四阶段时长/题量配置。
     *
     * <p>默认值体现了面试节奏设计：
     * <p>自我介绍（intro）短而少题（3-5 分钟、2-5 题）
     * <p>技术面（tech）与项目面（project）是主体（8-15 分钟、3-8 题）
     * <p>HR 面（hr）收尾轻量（3-5 分钟、2-5 题）
     */
    @Data
    public static class PhaseConfig {
        private DurationConfig intro = new DurationConfig(3, 5, 8, 2, 5);
        private DurationConfig tech = new DurationConfig(8, 10, 15, 3, 8);
        private DurationConfig project = new DurationConfig(8, 10, 15, 2, 5);
        private DurationConfig hr = new DurationConfig(3, 5, 8, 2, 5);
    }

    /**
     * 单个阶段的时长/题量配置。
     *
     * <p>三个时长阈值含义不同（对应阶段切换的三条规则）：
     * <ul>
     *   <li>{@code minDuration} —— 与 minQuestions 组合：达到最小时长且问满最少题数才允许切换</li>
     *   <li>{@code suggestedDuration} —— 建议切换时长（达到建议时长且问满最少题数建议切换，用于前端展示）</li>
     *   <li>{@code maxDuration} —— 硬上限，达到后强制切换（防止面试无限拖延）</li>
     * </ul>
     */
    @Data
    public static class DurationConfig {
        private int minDuration;
        private int suggestedDuration;
        private int maxDuration;
        private int minQuestions;
        private int maxQuestions;

        public DurationConfig(int min, int suggested, int max, int minQ, int maxQ) {
            this.minDuration = min;
            this.suggestedDuration = suggested;
            this.maxDuration = max;
            this.minQuestions = minQ;
            this.maxQuestions = maxQ;
        }
    }

    /**
     * 阿里云（Aliyun）语音服务凭据分组。
     *
     * <p>注意：与 {@link QwenConfig} 并存，二者目标服务不同——
     * 此处是阿里云智能语音交互（NLS）旧版 SDK 的配置，保留用于兼容；
     * 实时语音面试主链路实际使用 {@link QwenConfig}（DashScope 通义千问）。
     */
    @Data
    public static class AliyunConfig {
        private SttConfig stt = new SttConfig();
        private TtsConfig tts = new TtsConfig();
    }

    @Data
    public static class SttConfig {
        private String appKey;
        private String accessKey;
        /** 音频编码格式，opus 压缩率高，适合窄带宽传输 */
        private String format = "opus";
        private int sampleRate = 16000;
    }

    @Data
    public static class TtsConfig {
        private String appKey;
        private String accessKey;
        /** 阿里云 NLS 的女声音色 */
        private String voice = "xiaoyun";
        private String format = "mp3";
        private int sampleRate = 16000;
    }

    /**
     * 会话创建的限流配置（每会话/IP 次数与全局并发数）。
     */
    @Data
    public static class RateLimitConfig {
        private int maxPerSession = 10;
        private int maxPerIp = 3;
        private int maxConcurrent = 50;
    }

    /**
     * 前端音频采集参数。chunkDuration=2 秒即前端按 2 秒一个音频块推流，
     * 是「音频分块」粒度的统一约定，服务端按块转 Base64 后送 ASR。
     */
    @Data
    public static class AudioConfig {
        private String codec = "opus";
        private int sampleRate = 16000;
        private int bitRate = 24000;
        private int channels = 1;
        private int chunkDuration = 2000; // 2 seconds
    }

    @Data
    public static class QwenConfig {
        private AsrConfig asr = new AsrConfig();
        private QwenTtsConfig tts = new QwenTtsConfig();
    }

    /**
     * 通义千问实时 ASR（qwen3-asr-flash-realtime）配置。
     *
     * <p>关键设计：开启服务端 VAD（server_vad），静音 1 秒自动切句。
     * 由云端完成「一句话何时结束」的判定，客户端无需自研端点检测。
     */
    @Data
    public static class AsrConfig {
        /** DashScope 实时 ASR 的 WebSocket 端点 */
        private String url = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime";
        private String model = "qwen3-asr-flash-realtime";
        private String apiKey;
        private String language = "zh";
        private String format = "pcm";
        private int sampleRate = 16000;
        private boolean enableTurnDetection = true;
        /** VAD 由服务端执行，客户端只需推流 */
        private String turnDetectionType = "server_vad";
        private float turnDetectionThreshold = 0.0f;
        /** 静音多久判定为一句话结束（毫秒），过短会频繁切句，过长会吞掉停顿 */
        private int turnDetectionSilenceDurationMs = 1000;
    }

    /**
     * 通义千问实时 TTS（qwen3-tts-flash-realtime）配置。
     *
     * <p>mode=commit 表示「手动提交模式」：客户端 appendText 后显式 commit 才触发合成，
     * 便于在整句文本就绪后一次性合成，而不是边推边合。
     */
    @Data
    public static class QwenTtsConfig {
        private String model = "qwen3-tts-flash-realtime";
        private String apiKey;
        private String voice = "Cherry";
        private String format = "pcm";
        /** 注意与 ASR 的 16kHz 不同：TTS 输出 24kHz，WAV 头也按 24kHz 写 */
        private int sampleRate = 24000;
        private String mode = "commit";
        private String languageType = "Chinese";
        /** 语速倍率，1.0 为正常 */
        private float speechRate = 1.0f;
        private int volume = 60;
    }

    /**
     * 开场白配置：按技能方向选择开场问题模板。
     *
     * <p>选择优先级（见 {@code VoiceInterviewWebSocketHandler#buildOpeningQuestion}）：
     * skillQuestions 精确匹配 &gt; 算法类技能（algorithmSkills）&gt; 通用后端开场白。
     */
    @Data
    public static class OpeningConfig {
        /** 技能ID → 开场白模板。LinkedHashMap 保证配置文件的插入顺序，便于人工维护 */
        private Map<String, String> skillQuestions = new LinkedHashMap<>();
        /** 命中这些技能ID时使用特定开场白 */
        private List<String> algorithmSkills = List.of("bytedance-backend", "algorithm");
        /** algorithmSkills 开场白*/
        private String algorithmQuestion =
                "你好，我是本场面试官。先做一道算法与数据结构热身题：请你从“哈希表/堆/栈/队列/树/图”里选两个，结合一道你熟悉的题，口述“为什么选这个结构、核心步骤、时间复杂度、空间复杂度、边界条件与反例”。本场不需要写代码，重点看你的思路和取舍。";
        /** bytedance-backend 开场白*/
        private String backendQuestion =
                "你好，我是本场面试官。第一个问题：请用 1 分钟介绍一个你深度参与的项目，按三点回答：业务目标、你负责的核心模块、核心技术栈。说完我会立刻追问一个关键技术决策。";
    }

    /**
     * 上下文压缩配置。
     *
     * <p>开关与模式分离：enabled=false 时行为与引入压缩前完全一致（向后兼容），
     * enabled=true 后再由 mode 决定压缩策略。窗口大小与摘要批次越大，
     * LLM 摘要调用越少但上下文越模糊，是成本与保真度的权衡。
     */
    @Data
    public static class ContextCompressionConfig {
        /**
         * 是否启用上下文压缩。默认关闭，保证向后兼容（关闭时行为与改前完全一致）。
         */
        private boolean enabled = false;
        /**
         * 压缩模式：NONE=不压缩；WINDOW=仅保留最近窗口原文；SUMMARY=窗口原文+早期轮次增量摘要。
         */
        private Mode mode = Mode.SUMMARY;
        /**
         * 保留的最近轮次数量（滑动窗口大小）。
         */
        private int windowSize = 20;
        /**
         * 早期轮次每越过该批次数，触发一次增量摘要合并（降低 LLM 摘要调用频率）。
         */
        private int summaryBatchSize = 10;
    }

    public enum Mode {
        NONE, WINDOW, SUMMARY
    }
}

