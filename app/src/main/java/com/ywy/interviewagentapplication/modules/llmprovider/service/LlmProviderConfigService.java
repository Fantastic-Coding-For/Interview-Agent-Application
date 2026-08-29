package com.ywy.interviewagentapplication.modules.llmprovider.service;

import com.ywy.interviewagentapplication.common.ai.ApiPathResolver;
import com.ywy.interviewagentapplication.common.ai.LlmProviderRegistry;
import com.ywy.interviewagentapplication.common.config.LlmProviderProperties;
import com.ywy.interviewagentapplication.common.config.LlmProviderProperties.ProviderConfig;
import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.modules.llmprovider.dto.AsrConfigDTO;
import com.ywy.interviewagentapplication.modules.llmprovider.dto.AsrConfigRequest;
import com.ywy.interviewagentapplication.modules.llmprovider.dto.CreateProviderRequest;
import com.ywy.interviewagentapplication.modules.llmprovider.dto.DefaultProviderDTO;
import com.ywy.interviewagentapplication.modules.llmprovider.dto.ProviderDTO;
import com.ywy.interviewagentapplication.modules.llmprovider.dto.ProviderTestResult;
import com.ywy.interviewagentapplication.modules.llmprovider.dto.TtsConfigDTO;
import com.ywy.interviewagentapplication.modules.llmprovider.dto.TtsConfigRequest;
import com.ywy.interviewagentapplication.modules.llmprovider.dto.UpdateProviderRequest;
import com.ywy.interviewagentapplication.modules.llmprovider.model.LlmGlobalSettingEntity;
import com.ywy.interviewagentapplication.modules.llmprovider.model.LlmProviderEntity;
import com.ywy.interviewagentapplication.modules.llmprovider.repository.LlmGlobalSettingRepository;
import com.ywy.interviewagentapplication.modules.llmprovider.repository.LlmProviderRepository;
import com.ywy.interviewagentapplication.modules.voiceinterview.config.VoiceInterviewProperties;
import com.ywy.interviewagentapplication.modules.voiceinterview.service.QwenAsrService;
import com.ywy.interviewagentapplication.modules.voiceinterview.service.QwenTtsService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <h1>LLM Provider 配置服务（核心业务层）</h1>
 *
 * <p>本服务是 LLM Provider 管理的核心，承担以下职责：</p>
 * <ul>
 *   <li><b>Provider CRUD</b>：创建、查询、更新、删除 LLM Provider 配置</li>
 *   <li><b>双模数据源切换</b>：支持<b>数据库模式</b>（JPA 持久化 + API Key 加密）和
 *       <b>YAML 文件模式</b>（传统配置文件方式，兼容旧版）两种存储后端</li>
 *   <li><b>默认 Provider 管理</b>：设置和切换默认的聊天 Provider / Embedding Provider</li>
 *   <li><b>ASR/TTS 配置管理</b>：语音识别和语音合成的配置读写</li>
 *   <li><b>连通性测试</b>：通过 HTTP 请求验证 Provider 的 baseUrl 和 apiKey 是否可用</li>
 *   <li><b>配置文件持久化</b>：YAML 文本级编辑（保留注释和格式）和 .env 文件管理</li>
 * </ul>
 *
 * <h2>并发控制设计（核心）</h2>
 * <p>使用 {@link ReentrantReadWriteLock} 实现读写锁分离：读共享，写独占</p>
 *
 * <p><b>为什么用读写锁而不是 synchronized？</b></p>
 * <ol>
 *   <li>本服务<b>读多写少</b>：查询 Provider 列表、获取配置是高频操作，而创建/更新是低频操作</li>
 *   <li>synchronized 是互斥锁，即使全是读操作也会串行化——这在高并发场景下是性能瓶颈</li>
 *   <li>ReentrantReadWriteLock 允许多个读者同时持有锁，仅在写者到来时才阻塞新读者</li>
 *   <li>特别注意：所有读写方法都使用 {@code try { ... } finally { lock.unlock(); }}
 *       模式确保锁一定被释放，防止死锁</li>
 * </ol>
 *
 * <p><b>ReentrantReadWriteLock 的公平性说明：</b></p>
 * <ul>
 *   <li>本类使用默认的<b>非公平锁</b>（构造函数无参），吞吐量更高</li>
 *   <li>代价：连续不断的读请求可能导致写请求"饥饿"（一直拿不到锁）</li>
 *   <li>由于写操作在本业务中是低频的管理操作，饥饿风险较低</li>
 *   <li>如果需要公平性，可改为 {@code new ReentrantReadWriteLock(true)}</li>
 * </ul>
 *
 * <h2>数据源切换逻辑</h2>
 * <p>通过 {@link #isDatabaseBacked()} 判断当前运行模式：</p>
 * <ul>
 *   <li><b>数据库模式</b>：providerRepository、globalSettingRepository、encryptionService
 *       三者均不为 null（由 Spring 通过第一个构造函数注入）</li>
 *   <li><b>YAML 文件模式</b>：上述三个依赖为 null（通过第二个简化构造函数创建，用于兼容旧版）</li>
 * </ul>
 * <p>每个公共方法内部都先判断模式，再走对应的实现分支。</p>
 *
 * <h2>API Key 安全</h2>
 * <ul>
 *   <li><b>数据库模式</b>：API Key 通过 {@link ApiKeyEncryptionService} 加密后存储（nonce + ciphertext）</li>
 *   <li><b>YAML 模式</b>：API Key 以环境变量引用 {@code ${PROVIDER_XXX_API_KEY}} 形式写在 YAML 中，
 *       实际值存储在 .env 文件</li>
 *   <li><b>对外暴露</b>：返回给前端的 API Key 始终经过 mask 处理（如 {@code sk-***xyz}）</li>
 * </ul>
 *
 * @author ywy
 * @see LlmProviderRegistry 负责 Provider 实例的运行时注册和热加载（通过清空缓存实现）
 * @see ApiKeyEncryptionService 负责 API Key 的 AES 加密/解密
 * @see YamlTextEditor YAML 文本级编辑器（保留注释和格式）
 */
@Service
@Slf4j
public class LlmProviderConfigService {
    private final LlmProviderProperties properties;
    private final LlmProviderRegistry registry;
    private final LlmProviderRepository providerRepository;
    private final LlmGlobalSettingRepository globalSettingRepository;
    private final ApiKeyEncryptionService encryptionService;

    /**
     * 应用程序 YAML 配置文件的路径（如 application.yml）。
     * 用于 YAML 模式下的配置文件读写。
     */
    private final String yamlPath;

    /**
     * 环境变量文件路径（如 .env）。
     * 用于 YAML 模式下 API Key 的持久化存储。
     */
    private final String envPath;

    /**
     * <p>{@link ReentrantReadWriteLock} 是 JUC 提供的读写锁实现，核心特性：</p>
     * <ul>
     *   <li><b>读共享</b>：多个读线程可以同时持有 readLock，互不阻塞</li>
     *   <li><b>写独占</b>：writeLock 是排他的，写者阻塞所有读者和其他写者</li>
     *   <li><b>可重入</b>：持有锁的线程可以再次获取同一个锁（读写锁都支持）</li>
     *   <li><b>锁降级</b>：持有 writeLock 的线程可以获取 readLock，然后释放 writeLock（写→读降级）</li>
     *   <li><b>不支持锁升级</b>：持有 readLock 的线程不能直接获取 writeLock（必须先释放 readLock）</li>
     * </ul>
     *
     * <p><b>锁的释放模式：</b></p>
     * <p>所有读写操作都遵循以下 try-finally 模式，这是<b>强制性</b>的纪律：</p>
     * <pre>{@code
     *   rwLock.readLock().lock();    // 或 writeLock().lock()
     *   try {
     *       // 业务逻辑
     *   } finally {
     *       rwLock.readLock().unlock(); // 保证即使抛异常也释放锁
     *   }
     * }</pre>
     * <p>缺少 finally 块中的 unlock() 会导致其他线程永远阻塞等待这个锁的释放。</p>
     */
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    /**
     * 语音面试相关配置属性。
     */
    private final VoiceInterviewProperties voiceProperties;
    /**
     * ASR（语音识别）服务，用于配置变更后的热重载。
     */
    private final QwenAsrService asrService;
    /**
     * TTS（语音合成）服务，用于配置变更后的热重载。
     */
    private final QwenTtsService ttsService;

    /**
     * <h3>推荐 Embedding 模型映射表</h3>
     * <p>用于在用户错误填写了聊天模型名作为 Embedding 模型时，给出正确的建议。</p>
     * <p>Key = Provider ID（小写），Value = 该厂商推荐的 Embedding 模型名。</p>
     * <p>例如：dashscope（阿里百炼）→ text-embedding-v3，glm（智谱）→ embedding-3。</p>
     */
    private static final Map<String, String> RECOMMENDED_EMBEDDING_MODELS = Map.of(
            "dashscope", "text-embedding-v3",
            "glm", "embedding-3",
            "zhipu", "embedding-3",
            "baidu", "Embedding-V1",
            "minimax", "embo-01"
    );

    /**
     * <h3>完整构造函数（数据库模式）</h3>
     * <p>由 Spring 自动注入所有依赖。当 providerRepository、globalSettingRepository、
     * encryptionService 三者均被注入时，系统运行在<b>数据库模式</b>。</p>
     */
    @Autowired
    public LlmProviderConfigService(
            LlmProviderProperties properties,
            LlmProviderRegistry registry,
            LlmProviderRepository providerRepository,
            LlmGlobalSettingRepository globalSettingRepository,
            ApiKeyEncryptionService encryptionService,
            VoiceInterviewProperties voiceProperties,
            QwenAsrService asrService,
            QwenTtsService ttsService) {
        this.properties = properties;
        this.registry = registry;
        this.providerRepository = providerRepository;
        this.globalSettingRepository = globalSettingRepository;
        this.encryptionService = encryptionService;
        this.yamlPath = properties.getConfigYamlPath();
        this.envPath = properties.getConfigEnvPath();
        this.voiceProperties = voiceProperties;
        this.asrService = asrService;
        this.ttsService = ttsService;
    }

    /**
     * <h3>简化构造函数（YAML 文件模式，用于兼容旧版）</h3>
     * <p>当没有数据库依赖时使用此构造函数。此时 providerRepository、globalSettingRepository、
     * encryptionService 均为 null，系统回退到 YAML + .env 文件存储模式。</p>
     * <p>通过 {@code this(...)} 调用完整构造函数，将三个数据库依赖传为 null。</p>
     */
    public LlmProviderConfigService(
            LlmProviderProperties properties,
            LlmProviderRegistry registry,
            VoiceInterviewProperties voiceProperties,
            QwenAsrService asrService,
            QwenTtsService ttsService) {
        this(properties, registry, null, null, null, voiceProperties, asrService, ttsService);
    }

    /**
     * <h3>启动时校验：确保配置文件路径的父目录可写</h3>
     * <p>在 Spring Bean 初始化完成后（{@link PostConstruct}）自动执行。</p>
     * <p>如果父目录不存在则尝试创建；如果不可写则抛出异常，<b>阻止应用启动</b>（fail-fast 策略）。</p>
     * <p>这样可以在应用启动阶段就发现配置问题，而不是等到运行时才发现无法写入配置文件。</p>
     */
    @PostConstruct
    public void validateWritablePaths() {
        ensureParentWritable(yamlPath, "config-yaml-path");
        ensureParentWritable(envPath, "config-env-path");
    }

    /**
     * 校验指定路径的父目录是否可写。
     * <ol>
     *   <li>如果路径未配置（null 或空串），仅记录 warn 日志，不抛异常。允许无配置文件模式</li>
     *   <li>获取绝对路径的父目录</li>
     *   <li>如果不存在对应目录则尝试创建（{@link Files#createDirectories} 会创建所有不存在的父目录）</li>
     *   <li>检查目录是否可写</li>
     * </ol>
     *
     * @param rawPath 原始配置路径（可能包含相对路径）
     * @param label   配置项名称（用于日志和异常消息）
     * @throws BusinessException 父目录不可创建或不可写时抛出，错误码为 PROVIDER_CONFIG_WRITE_FAILED
     */
    private void ensureParentWritable(String rawPath, String label) {
        if (rawPath == null || rawPath.isBlank()) {
            log.warn("{} is not configured; runtime Provider edits will be skipped", label);
            return;
        }
        Path parent = Path.of(rawPath).toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED,
                    label + " 的父目录不可创建: " + parent, e);
        }
        if (!Files.isWritable(parent)) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED,
                    label + " 的父目录不可写: " + parent);
        }
        log.info("{} resolved to {} (parent writable)", label, rawPath);
    }

    // ===== Read operations (read lock) =====
    // 以下所有方法使用 readLock，允许并发读取

    /**
     * <h3>获取所有 Provider 列表</h3>
     *
     * <p><b>读锁保护</b>：使用 readLock，多个查询请求可以并发执行。</p>
     *
     * <p><b>数据库模式 vs YAML 模式：</b></p>
     * <ul>
     *   <li><b>YAML 模式</b>：直接从 {@link LlmProviderProperties#getProviders()} 读取内存中的配置 Map，
     *       转换为 DTO 列表。支持 Embedding 时自动推断：显式设置 supportsEmbedding=true 或
     *       配置了 embeddingModel 都视为支持。</li>
     *   <li><b>数据库模式</b>：从数据库查询所有 Provider 实体，同时查出全局设置来确定哪个是默认 Provider。
     *       API Key 解密后额外进行 mask 处理。</li>
     * </ul>
     *
     * @return Provider 配置的 DTO 列表（保证不为 null，无 Provider 时返回空列表）
     */
    public List<ProviderDTO> listProviders() {
        rwLock.readLock().lock();
        try {
            if (!isDatabaseBacked()) {
                Map<String, ProviderConfig> providers = properties.getProviders();
                if (providers == null) return List.of();
                return providers.entrySet().stream()
                        .map(e -> ProviderDTO.builder()
                                .id(e.getKey())
                                .baseUrl(e.getValue().getBaseUrl())
                                .maskedApiKey(maskApiKey(e.getValue().getApiKey()))
                                .model(e.getValue().getModel())
                                .embeddingModel(e.getValue().getEmbeddingModel())
                                .embeddingDimensions(resolveEmbeddingDimensions(e.getValue().getEmbeddingDimensions()))
                                .supportsEmbedding(Boolean.TRUE.equals(e.getValue().getSupportsEmbedding())
                                        || trimOrNull(e.getValue().getEmbeddingModel()) != null)
                                .temperature(e.getValue().getTemperature())
                                .defaultChatProvider(e.getKey().equals(properties.getDefaultProvider()))
                                .defaultEmbeddingProvider(e.getKey().equals(properties.getDefaultEmbeddingProvider()))
                                .build())
                        .toList();
            }
            LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
            return providerRepository.findAll().stream()
                    .map(provider -> ProviderDTO.builder()
                            .id(provider.getId())
                            .baseUrl(provider.getBaseUrl())
                            .maskedApiKey(maskApiKey(decryptApiKey(provider)))
                            .model(provider.getModel())
                            .embeddingModel(provider.getEmbeddingModel())
                            .embeddingDimensions(resolveEmbeddingDimensions(provider.getEmbeddingDimensions()))
                            .supportsEmbedding(provider.isSupportsEmbedding())
                            .temperature(provider.getTemperature())
                            .defaultChatProvider(provider.getId().equals(setting.getDefaultChatProviderId()))
                            .defaultEmbeddingProvider(provider.getId().equals(setting.getDefaultEmbeddingProviderId()))
                            .build())
                    .toList();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * <h3>根据指定的 Provider ID 获取单个 Provider 的详细配置</h3>
     *
     * <p><b>读锁保护</b>。</p>
     *
     * <p>与 {@link #listProviders()} 逻辑相同，但只返回指定的一个 Provider。
     * 数据库模式下先查出全局设置（用于判断是否默认），再查出 Provider 实体。</p>
     *
     * @param id Provider 唯一标识
     * @return Provider 的 DTO 对象
     * @throws BusinessException 如果指定 id 的 Provider 不存在（PROVIDER_NOT_FOUND）
     */
    public ProviderDTO getProvider(String id) {
        rwLock.readLock().lock();
        try {
            if (!isDatabaseBacked()) {
                ProviderConfig config = getLegacyProviderConfigOrThrow(id);
                return ProviderDTO.builder()
                        .id(id)
                        .baseUrl(config.getBaseUrl())
                        .maskedApiKey(maskApiKey(config.getApiKey()))
                        .model(config.getModel())
                        .embeddingModel(config.getEmbeddingModel())
                        .embeddingDimensions(resolveEmbeddingDimensions(config.getEmbeddingDimensions()))
                        .supportsEmbedding(Boolean.TRUE.equals(config.getSupportsEmbedding())
                                || trimOrNull(config.getEmbeddingModel()) != null)
                        .temperature(config.getTemperature())
                        .defaultChatProvider(id.equals(properties.getDefaultProvider()))
                        .defaultEmbeddingProvider(id.equals(properties.getDefaultEmbeddingProvider()))
                        .build();
            }
            LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
            LlmProviderEntity provider = getProviderEntityOrThrow(id);
            return ProviderDTO.builder()
                    .id(id)
                    .baseUrl(provider.getBaseUrl())
                    .maskedApiKey(maskApiKey(decryptApiKey(provider)))
                    .model(provider.getModel())
                    .embeddingModel(provider.getEmbeddingModel())
                    .embeddingDimensions(resolveEmbeddingDimensions(provider.getEmbeddingDimensions()))
                    .supportsEmbedding(provider.isSupportsEmbedding())
                    .temperature(provider.getTemperature())
                    .defaultChatProvider(id.equals(setting.getDefaultChatProviderId()))
                    .defaultEmbeddingProvider(id.equals(setting.getDefaultEmbeddingProviderId()))
                    .build();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * <h3>返回当前设定的默认 LLM Model 和默认 Embedding Model 对应的 Provider ID</h3>
     * <p><b>读锁保护</b>。</p>
     * @return 包含 defaultProvider 和 defaultEmbeddingProvider 两个 ID 的 DTO
     */
    public DefaultProviderDTO getDefaultProvider() {
        rwLock.readLock().lock();
        try {
            if (!isDatabaseBacked()) {
                return new DefaultProviderDTO(properties.getDefaultProvider(), properties.getDefaultEmbeddingProvider());
            }
            LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
            return new DefaultProviderDTO(
                    setting.getDefaultChatProviderId(),
                    setting.getDefaultEmbeddingProviderId());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * <h3>获取 ASR（语音识别）配置</h3>
     *
     * <p><b>读锁保护</b>。</p>
     *
     * <p>从 {@link VoiceInterviewProperties} 中读取 Qwen ASR 的完整配置信息，包括
     * WebSocket 地址、模型名、API Key（mask 处理）、语言、音频格式、采样率、
     * 以及语音活动检测（Turn Detection）相关参数。</p>
     *
     * @return ASR 配置的 DTO
     */
    public AsrConfigDTO getAsrConfig() {
        rwLock.readLock().lock();
        try {
            VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
            return AsrConfigDTO.builder()
                    .url(asr.getUrl())
                    .model(asr.getModel())
                    .maskedApiKey(maskApiKey(asr.getApiKey()))
                    .language(asr.getLanguage())
                    .format(asr.getFormat())
                    .sampleRate(asr.getSampleRate())
                    .enableTurnDetection(asr.isEnableTurnDetection())
                    .turnDetectionType(asr.getTurnDetectionType())
                    .turnDetectionThreshold(asr.getTurnDetectionThreshold())
                    .turnDetectionSilenceDurationMs(asr.getTurnDetectionSilenceDurationMs())
                    .build();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * <h3>获取 TTS（语音合成）配置</h3>
     *
     * <p><b>读锁保护</b>。</p>
     *
     * <p>从 {@link VoiceInterviewProperties} 中读取 Qwen TTS 配置，包括模型、音色、
     * 音频格式、采样率、语速、音量等参数。</p>
     *
     * @return TTS 配置的 DTO
     */
    public TtsConfigDTO getTtsConfig() {
        rwLock.readLock().lock();
        try {
            VoiceInterviewProperties.QwenTtsConfig tts = voiceProperties.getQwen().getTts();
            return TtsConfigDTO.builder()
                    .model(tts.getModel())
                    .maskedApiKey(maskApiKey(tts.getApiKey()))
                    .voice(tts.getVoice())
                    .format(tts.getFormat())
                    .sampleRate(tts.getSampleRate())
                    .mode(tts.getMode())
                    .languageType(tts.getLanguageType())
                    .speechRate(tts.getSpeechRate())
                    .volume(tts.getVolume())
                    .build();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * <h3>测试指定 Provider 的连通性</h3>
     *
     * <p><b>读锁保护</b>（测试不修改数据，只是发送 HTTP 探测请求）。</p>
     *
     * <p>具体测试逻辑委托给 {@link #doTestProvider(ProviderRuntimeConfig, String)}，
     * 本方法只负责根据不同的数据源模式构造 {@link ProviderRuntimeConfig} 实例。</p>
     *
     * @param id 要测试的 Provider ID
     * @return 测试结果，包含成功/失败状态、消息和模型名
     */
    public ProviderTestResult testProvider(String id) {
        rwLock.readLock().lock();
        try {
            ProviderRuntimeConfig config = isDatabaseBacked()
                    ? getProviderRuntimeConfigOrThrow(id)
                    : toRuntimeConfig(getLegacyProviderConfigOrThrow(id));
            return doTestProvider(config, id);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * <h3>测试 ASR WebSocket 连接的连通性</h3>
     *
     * <p><b>读锁保护</b>。</p>
     *
     * <p>注意：这里<b>不是真正的 WebSocket 握手测试</b>，而是 TCP Socket 连接测试。
     * 只是验证目标主机和端口是否可达（超时 5 秒），实际 WebSocket 握手需要更复杂的测试。</p>
     *
     * <p>端口推断逻辑：</p>
     * <ul>
     *   <li>URL 中指定了端口 → 使用指定端口</li>
     *   <li>wss 协议 → 默认 443</li>
     *   <li>ws 协议 → 默认 80</li>
     * </ul>
     *
     * @return 测试结果（成功则包含 "WebSocket 连接成功" 消息）
     */
    public ProviderTestResult testAsrConfig() {
        rwLock.readLock().lock();
        try {
            VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
            try {
                java.net.URI wsUri = java.net.URI.create(asr.getUrl());
                String host = wsUri.getHost();
                int port = wsUri.getPort() > 0 ? wsUri.getPort() : (wsUri.getScheme().equals("wss") ? 443 : 80);
                java.net.InetSocketAddress address = new java.net.InetSocketAddress(host, port);
                java.net.Socket socket = new java.net.Socket();
                socket.connect(address, 5000);
                socket.close();
                return ProviderTestResult.builder()
                        .success(true)
                        .message("ASR WebSocket 连接成功: " + host)
                        .model(asr.getModel())
                        .build();
            } catch (Exception e) {
                return ProviderTestResult.builder()
                        .success(false)
                        .message("ASR 连接失败: " + e.getMessage())
                        .model(asr.getModel())
                        .build();
            }
        } finally {
            rwLock.readLock().unlock();
        }
    }

    // ===== Write operations (write lock) =====
    // 以下所有方法使用 writeLock，独占执行，阻塞所有其他读写线程

    /**
     * <h3>创建新的 Provider</h3>
     *
     * <p><b>写锁保护</b>：创建操作是排他的，阻塞所有读写。同时使用 {@link Transactional}
     * 保证数据库操作的原子性。</p>
     *
     * <p><b>数据库模式流程：</b></p>
     * <ol>
     *   <li>检查 Provider ID 是否已存在（去重）</li>
     *   <li>校验必填字段（baseUrl, model, apiKey）</li>
     *   <li>解析 Embedding 相关配置（模型名、维度、是否支持）</li>
     *   <li>校验 Embedding 配置合法性（不能填聊天模型名、维度必须为正整数）</li>
     *   <li>加密 API Key（AES 加密，得到 nonce + ciphertext）</li>
     *   <li>持久化到数据库</li>
     *   <li>热加载 Provider 注册中心（通过清空缓存实现）</li>
     * </ol>
     *
     * @param request 创建请求 DTO（包含 id, baseUrl, apiKey, model 等）
     * @throws BusinessException 如果 Provider 已存在或必填字段为空
     */
    @Transactional
    public void createProvider(CreateProviderRequest request) {
        rwLock.writeLock().lock();
        try {
            if (!isDatabaseBacked()) {
                createProviderLegacy(request);
                return;
            }
            String providerId = trimOrNull(request.id());
            if (providerRepository.existsById(providerId)) {
                throw new BusinessException(ErrorCode.PROVIDER_ALREADY_EXISTS,
                        "Provider '" + request.id() + "' 已存在");
            }
            String baseUrl = requireNonBlank(request.baseUrl(), "baseUrl");
            String model = requireNonBlank(request.model(), "model");
            String apiKey = requireNonBlank(request.apiKey(), "apiKey");
            String embeddingModel = trimOrNull(request.embeddingModel());
            Integer embeddingDimensions = resolveEmbeddingDimensions(request.embeddingDimensions());
            boolean supportsEmbedding = request.supportsEmbedding() != null
                    ? request.supportsEmbedding()
                    : embeddingModel != null;
            validateEmbeddingConfig(providerId, supportsEmbedding, embeddingModel, embeddingDimensions);

            ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(apiKey);
            providerRepository.save(LlmProviderEntity.builder()
                    .id(providerId)
                    .baseUrl(baseUrl)
                    .apiKeyNonce(encrypted.nonce())
                    .apiKeyCiphertext(encrypted.ciphertext())
                    .model(model)
                    .embeddingModel(embeddingModel)
                    .embeddingDimensions(embeddingDimensions)
                    .supportsEmbedding(supportsEmbedding)
                    .temperature(request.temperature())
                    .enabled(true)
                    .builtin(false)
                    .build());
            registry.reload();
            log.info("Created provider: id={}, baseUrl={}, model={}", providerId, baseUrl, model);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * <h3>更新已有的 Provider 配置</h3>
     *
     * <p><b>写锁保护 + 事务</b>。</p>
     *
     * <p>支持<b>部分更新</b>：只更新请求中提供的字段（非 null 字段），未提供的字段保持不变。
     * 特殊处理：如果请求中提供了字符串字段但内容为空，会抛出异常（不允许清空必填字段）。</p>
     *
     * <p>API Key 变更时，需要重新加密（新的 nonce + ciphertext）。</p>
     * 热加载 Provider 注册中心（通过清空缓存实现）。
     *
     * @param id      Provider ID
     * @param request 更新请求 DTO（所有字段均可选）
     * @throws BusinessException 如果 Provider 不存在或字段校验失败
     */
    @Transactional
    public void updateProvider(String id, UpdateProviderRequest request) {
        rwLock.writeLock().lock();
        try {
            if (!isDatabaseBacked()) {
                updateProviderLegacy(id, request);
                return;
            }
            LlmProviderEntity provider = getProviderEntityOrThrow(id);

            String trimmedBaseUrl = trimOrNull(request.baseUrl());
            if (request.baseUrl() != null && trimmedBaseUrl == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "baseUrl 不能为空字符串");
            }
            String trimmedModel = trimOrNull(request.model());
            if (request.model() != null && trimmedModel == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "model 不能为空字符串");
            }
            String trimmedApiKey = trimOrNull(request.apiKey());
            if (request.apiKey() != null && trimmedApiKey == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "apiKey 不能为空字符串");
            }

            if (trimmedBaseUrl != null) provider.setBaseUrl(trimmedBaseUrl);
            if (trimmedModel != null) provider.setModel(trimmedModel);
            if (request.embeddingModel() != null) {
                provider.setEmbeddingModel(trimOrNull(request.embeddingModel()));
            }
            if (request.embeddingDimensions() != null) {
                provider.setEmbeddingDimensions(resolveEmbeddingDimensions(request.embeddingDimensions()));
            }
            if (request.supportsEmbedding() != null) {
                provider.setSupportsEmbedding(request.supportsEmbedding());
            }
            validateEmbeddingConfig(
                    id,
                    provider.isSupportsEmbedding(),
                    provider.getEmbeddingModel(),
                    resolveEmbeddingDimensions(provider.getEmbeddingDimensions()));
            if (request.temperature() != null) {
                provider.setTemperature(request.temperature());
            }
            if (trimmedApiKey != null) {
                ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(trimmedApiKey);
                provider.setApiKeyNonce(encrypted.nonce());
                provider.setApiKeyCiphertext(encrypted.ciphertext());
            }

            providerRepository.save(provider);
            registry.reload();
            log.info("Updated provider: id={}", id);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * <h3>删除 Provider</h3>
     *
     * <p><b>写锁保护 + 事务</b>。</p>
     *
     * <p><b>保护规则</b>：不允许删除已被设为默认的 Provider（默认聊天或默认 Embedding），
     * 需要先切换默认 Provider 再删除。这确保了系统始终有一个有效的默认 Provider。</p>
     * 热加载 Provider 注册中心（通过清空缓存实现）
     *
     * @param id Provider ID
     * @throws BusinessException 如果该 Provider 是默认 Provider（PROVIDER_DEFAULT_CANNOT_DELETE）
     */
    @Transactional
    public void deleteProvider(String id) {
        rwLock.writeLock().lock();
        try {
            if (!isDatabaseBacked()) {
                deleteProviderLegacy(id);
                return;
            }
            LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
            if (id.equals(setting.getDefaultChatProviderId()) || id.equals(setting.getDefaultEmbeddingProviderId())) {
                throw new BusinessException(ErrorCode.PROVIDER_DEFAULT_CANNOT_DELETE,
                        "默认 Provider '" + id + "' 不可删除，请先切换默认 Provider");
            }
            getProviderEntityOrThrow(id);

            providerRepository.deleteById(id);
            registry.reload();
            log.info("Deleted provider: id={}", id);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * <h3>更新默认 LLM Model 对应的 Provider</h3>
     * <p><b>写锁保护 + 事务</b>。</p>
     * <p>热加载 Provider 注册中心（通过清空缓存实现）；保证数据库表的单例更新，而非追加</p>
     *
     * @param request 包含 defaultProvider 字段的请求 DTO
     * @throws BusinessException 如果指定的 Provider 不存在
     */
    @Transactional
    public void updateDefaultProvider(DefaultProviderDTO request) {
        rwLock.writeLock().lock();
        try {
            if (!isDatabaseBacked()) {
                updateDefaultProviderLegacy(request);
                return;
            }
            String providerId = trimOrNull(request.defaultProvider());
            if (providerId == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "defaultProvider 不能为空");
            }
            getProviderEntityOrThrow(providerId);
            LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
            setting.setDefaultChatProviderId(providerId);
            globalSettingRepository.save(setting);
            registry.reload();
            log.info("Updated default provider: {}", providerId);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * <h3>更新默认 Embedding Model 对应的 Provider</h3>
     *
     * <p><b>写锁保护 + 事务</b>。</p>
     *
     * <p>与更新默认 LLM Model 对应的 Provider不同，除了做存在性校验，还会<b>额外校验</b>：
     * 目标 Provider 必须支持 Embedding（supportsEmbedding=true 且 embeddingModel 不为空），
     * 并且 embeddingModel 不能是聊天模型名、维度必须为正整数。</p>
     * <p>热加载 Provider 注册中心（通过清空缓存实现）；保证数据库表的单例更新，而非追加</p>
     *
     * @param request 包含 defaultEmbeddingProvider 字段的请求 DTO
     * @throws BusinessException 如果 Provider 不存在或不支持 Embedding，或模型名称错误/维度错误
     */
    @Transactional
    public void updateDefaultEmbeddingProvider(DefaultProviderDTO request) {
        rwLock.writeLock().lock();
        try {
            String providerId = trimOrNull(request.defaultEmbeddingProvider());
            if (providerId == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "defaultEmbeddingProvider 不能为空");
            }
            LlmProviderEntity provider = getProviderEntityOrThrow(providerId);
            String embeddingModel = trimOrNull(provider.getEmbeddingModel());
            if (!provider.isSupportsEmbedding() || embeddingModel == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "Provider '" + providerId + "' 不支持 Embedding，不能设为默认向量服务");
            }
            validateEmbeddingConfig(
                    providerId,
                    true,
                    embeddingModel,
                    resolveEmbeddingDimensions(provider.getEmbeddingDimensions()));
            LlmGlobalSettingEntity setting = getGlobalSettingOrThrow();
            setting.setDefaultEmbeddingProviderId(providerId);
            globalSettingRepository.save(setting);
            registry.reload();
            log.info("Updated default embedding provider: {}", providerId);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * <h3>更新 ASR 配置（对应写入 YAML/.env）</h3>
     *
     * <p><b>写锁保护</b>（无 @Transactional，因为操作的是文件系统而非数据库）。</p>
     *
     * <p>支持部分更新（null 字段保持不变）。特殊行为：</p>
     * <ul>
     *   <li>API Key 是 ASR 和 TTS <b>共享</b>的（都使用百炼 API Key），所以更新 ASR 的
     *       API Key 时，会同时更新 TTS 的 API Key 和 .env 文件中的环境变量</li>
     *   <li>配置写入后，调用 {@code asrService.reload()} 使新配置立即生效</li>
     *   <li>如果包含 API Key 变更，额外调用 {@code ttsService.reload()} 使 TTS 也使用新 Key</li>
     * </ul>
     *
     * @param request ASR 配置更新请求
     */
    public void updateAsrConfig(AsrConfigRequest request) {
        rwLock.writeLock().lock();
        try {
            VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
            VoiceInterviewProperties.QwenTtsConfig tts = voiceProperties.getQwen().getTts();
            if (request.url() != null) asr.setUrl(request.url());
            if (request.model() != null) asr.setModel(request.model());
            if (request.language() != null) asr.setLanguage(request.language());
            if (request.format() != null) asr.setFormat(request.format());
            if (request.sampleRate() != null) asr.setSampleRate(request.sampleRate());
            if (request.enableTurnDetection() != null) asr.setEnableTurnDetection(request.enableTurnDetection());
            if (request.turnDetectionType() != null) asr.setTurnDetectionType(request.turnDetectionType());
            if (request.turnDetectionThreshold() != null) asr.setTurnDetectionThreshold(request.turnDetectionThreshold());
            if (request.turnDetectionSilenceDurationMs() != null) asr.setTurnDetectionSilenceDurationMs(request.turnDetectionSilenceDurationMs());
            if (request.apiKey() != null) {
                asr.setApiKey(request.apiKey());
                tts.setApiKey(request.apiKey());
                updateEnvValue("AI_BAILIAN_API_KEY", request.apiKey());
            }

            writeAsrConfigToYaml(asr);
            asrService.reload(voiceProperties);
            if (request.apiKey() != null) {
                ttsService.reload(voiceProperties);
            }
            log.info("Updated ASR config");
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * <h3>更新 TTS 配置（对应写入 YAML/.env）</h3>
     *
     * <p><b>写锁保护</b>。逻辑与 {@link #updateAsrConfig(AsrConfigRequest)} 对称：</p>
     * <ul>
     *   <li>支持部分更新</li>
     *   <li>API Key 同步更新到 ASR 配置和 .env 文件</li>
     *   <li>配置写入后热加载 TTS 服务；如果包含 API Key 变更则同步热加载 ASR 服务</li>
     * </ul>
     *
     * @param request TTS 配置更新请求
     */
    public void updateTtsConfig(TtsConfigRequest request) {
        rwLock.writeLock().lock();
        try {
            VoiceInterviewProperties.AsrConfig asr = voiceProperties.getQwen().getAsr();
            VoiceInterviewProperties.QwenTtsConfig tts = voiceProperties.getQwen().getTts();
            if (request.model() != null) tts.setModel(request.model());
            if (request.voice() != null) tts.setVoice(request.voice());
            if (request.format() != null) tts.setFormat(request.format());
            if (request.sampleRate() != null) tts.setSampleRate(request.sampleRate());
            if (request.mode() != null) tts.setMode(request.mode());
            if (request.languageType() != null) tts.setLanguageType(request.languageType());
            if (request.speechRate() != null) tts.setSpeechRate(request.speechRate());
            if (request.volume() != null) tts.setVolume(request.volume());
            if (request.apiKey() != null) {
                tts.setApiKey(request.apiKey());
                asr.setApiKey(request.apiKey());
                updateEnvValue("AI_BAILIAN_API_KEY", request.apiKey());
            }

            writeTtsConfigToYaml(tts);
            ttsService.reload(voiceProperties);
            if (request.apiKey() != null) {
                asrService.reload(voiceProperties);
            }
            log.info("Updated TTS config");
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * <h3>手动热加载所有 Provider</h3>
     * <p>通过 {@link LlmProviderRegistry} 清空 provider 相关缓存，下次使用时动态加载。
     * 通常在外部直接修改了配置文件或数据库后调用。</p>
     */
    public void reloadProviders() {
        registry.reload();
        log.info("Manual provider reload triggered");
    }

    // ===== Internal helpers =====
    // 内部私有方法

    /**
     * <h3>判断当前是否运行在数据库模式</h3>
     *
     * <p>三个数据库相关依赖<b>全部不为 null</b> 时才认为是数据库模式。
     * 任何一个为 null 则回退到 YAML 文件模式。</p>
     *
     * @return true 表示数据库模式，false 表示 YAML 文件模式
     */
    private boolean isDatabaseBacked() {
        return providerRepository != null && globalSettingRepository != null && encryptionService != null;
    }

    /**
     * 获取 YAML 模式下的 Provider 配置 Map，如果未初始化则抛异常。
     */
    private Map<String, ProviderConfig> getLegacyProvidersOrThrow() {
        Map<String, ProviderConfig> providers = properties.getProviders();
        if (providers == null) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                    "Provider 配置未初始化");
        }
        return providers;
    }

    /**
     * 获取 YAML 模式下指定 ID 的 Provider 配置，不存在时抛异常。
     */
    ProviderConfig getLegacyProviderConfigOrThrow(String id) {
        ProviderConfig config = getLegacyProvidersOrThrow().get(id);
        if (config == null) {
            throw new BusinessException(ErrorCode.PROVIDER_NOT_FOUND,
                    "Provider '" + id + "' 不存在");
        }
        return config;
    }

    /**
     * YAML 模式：创建新 Provider。
     * <p>流程：校验 → 构造 ProviderConfig → 写入 YAML → 写入 .env → 热加载。</p>
     */
    private void createProviderLegacy(CreateProviderRequest request) {
        Map<String, ProviderConfig> providers = getLegacyProvidersOrThrow();
        if (providers.containsKey(request.id())) {
            throw new BusinessException(ErrorCode.PROVIDER_ALREADY_EXISTS,
                    "Provider '" + request.id() + "' 已存在");
        }

        ProviderConfig config = new ProviderConfig();
        config.setBaseUrl(request.baseUrl());
        config.setApiKey(request.apiKey());
        config.setModel(request.model());
        config.setEmbeddingModel(request.embeddingModel());
        config.setEmbeddingDimensions(request.embeddingDimensions());
        config.setSupportsEmbedding(request.supportsEmbedding());
        config.setTemperature(request.temperature());
        providers.put(request.id(), config);

        String envKey = toEnvKey(request.id());
        writeProviderToYaml(request.id(), config, envKey);
        writeEnvValue(envKey, request.apiKey());
        registry.reload();
    }

    /**
     * YAML 模式：更新已有 Provider。逻辑与数据库模式类似，但操作的是内存 Map 和 YAML 文件。
     */
    private void updateProviderLegacy(String id, UpdateProviderRequest request) {
        ProviderConfig config = getLegacyProviderConfigOrThrow(id);
        String trimmedBaseUrl = trimOrNull(request.baseUrl());
        if (request.baseUrl() != null && trimmedBaseUrl == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "baseUrl 不能为空字符串");
        }
        String trimmedModel = trimOrNull(request.model());
        if (request.model() != null && trimmedModel == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "model 不能为空字符串");
        }
        String trimmedApiKey = trimOrNull(request.apiKey());
        if (request.apiKey() != null && trimmedApiKey == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "apiKey 不能为空字符串");
        }

        if (trimmedBaseUrl != null) config.setBaseUrl(trimmedBaseUrl);
        if (trimmedModel != null) config.setModel(trimmedModel);
        if (request.embeddingModel() != null) {
            config.setEmbeddingModel(trimOrNull(request.embeddingModel()));
        }
        if (request.embeddingDimensions() != null) {
            config.setEmbeddingDimensions(resolveEmbeddingDimensions(request.embeddingDimensions()));
        }
        if (request.supportsEmbedding() != null) {
            config.setSupportsEmbedding(request.supportsEmbedding());
        }
        if (request.temperature() != null) {
            config.setTemperature(request.temperature());
        }
        if (trimmedApiKey != null) {
            config.setApiKey(trimmedApiKey);
            updateEnvValue(toEnvKey(id), trimmedApiKey);
        }

        writeProviderToYaml(id, config, toEnvKey(id));
        registry.reload();
    }

    /**
     * YAML 模式：删除 Provider。不允许删除默认 Provider。
     */
    private void deleteProviderLegacy(String id) {
        if (id.equals(properties.getDefaultProvider())) {
            throw new BusinessException(ErrorCode.PROVIDER_DEFAULT_CANNOT_DELETE,
                    "默认 Provider '" + id + "' 不可删除，请先切换默认 Provider");
        }
        getLegacyProviderConfigOrThrow(id);
        getLegacyProvidersOrThrow().remove(id);
        String envKey = toEnvKey(id);
        removeProviderFromYaml(id);
        removeFromEnv(envKey);
        registry.reload();
    }

    /**
     * YAML 模式：更新默认 Provider。
     */
    private void updateDefaultProviderLegacy(DefaultProviderDTO request) {
        String providerId = trimOrNull(request.defaultProvider());
        if (providerId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "defaultProvider 不能为空");
        }
        getLegacyProviderConfigOrThrow(providerId);
        properties.setDefaultProvider(providerId);
        writeDefaultProviderToYaml(providerId);
        registry.reload();
    }

    /**
     * 将 YAML 模式下的 {@link ProviderConfig} 转换为 {@link ProviderRuntimeConfig} record，
     * 用于连通性测试。
     */
    private ProviderRuntimeConfig toRuntimeConfig(ProviderConfig config) {
        return new ProviderRuntimeConfig(
                config.getBaseUrl(),
                config.getApiKey(),
                config.getModel(),
                config.getEmbeddingModel(),
                resolveEmbeddingDimensions(config.getEmbeddingDimensions()),
                Boolean.TRUE.equals(config.getSupportsEmbedding()) || trimOrNull(config.getEmbeddingModel()) != null,
                config.getTemperature()
        );
    }

    /**
     * 数据库模式：查询 Provider 实体，不存在时抛出 PROVIDER_NOT_FOUND 异常。
     */
    LlmProviderEntity getProviderEntityOrThrow(String id) {
        return providerRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_NOT_FOUND,
                        "Provider '" + id + "' 不存在"));
    }

    /**
     * 数据库模式：查询全局设置单例实体（ID 固定为 SINGLETON_ID），
     * 不存在时说明系统尚未初始化。
     */
    private LlmGlobalSettingEntity getGlobalSettingOrThrow() {
        return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
                .orElseThrow(() -> new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                        "默认 Provider 配置未初始化"));
    }

    /**
     * 数据库模式：构建 Provider 运行时配置（解密 API Key），用于连通性测试。
     */
    private ProviderRuntimeConfig getProviderRuntimeConfigOrThrow(String id) {
        LlmProviderEntity provider = getProviderEntityOrThrow(id);
        return new ProviderRuntimeConfig(
                provider.getBaseUrl(),
                decryptApiKey(provider),
                provider.getModel(),
                provider.getEmbeddingModel(),
                resolveEmbeddingDimensions(provider.getEmbeddingDimensions()),
                provider.isSupportsEmbedding(),
                provider.getTemperature()
        );
    }

    /**
     * 解密 Provider 实体中存储的 API Key（nonce + ciphertext → 明文）。
     */
    private String decryptApiKey(LlmProviderEntity provider) {
        return encryptionService.decrypt(provider.getApiKeyNonce(), provider.getApiKeyCiphertext());
    }

    /**
     * <h3>对 API Key 进行脱敏处理（mask）</h3>
     *
     * <p>规则：</p>
     * <ul>
     *   <li>null 或长度 ≤ 6 → 返回 {@code "***"}</li>
     *   <li>长度 > 6 → 保留前 3 位和后 3 位，中间用 {@code ***} 替代</li>
     * </ul>
     *
     * <p>示例：{@code "sk-abcdefghijklmn"} → {@code "sk-***lmn"}</p>
     *
     * <p>访问修饰符为 package-private，供测试使用。</p>
     */
    public String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 6) {
            return "***";
        }
        return apiKey.substring(0, 3) + "***" + apiKey.substring(apiKey.length() - 3);
    }

    /**
     * 用于缩略日志输出的内容：去除多余空白，超过 200 字符则截断并加 "..."。
     */
    private String abbreviate(String text) {
        if (text == null || text.isBlank()) {
            return "[no body]";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 200) {
            return normalized;
        }
        return normalized.substring(0, 200) + "...";
    }

    /**
     * <h3>构建连通性测试的候选 URL 列表</h3>
     *
     * <p>LLM API 有两个常见的 endpoint 路径模式：</p>
     * <ol>
     *   <li><b>不带版本号</b>：{@code {baseUrl}/chat/completions} —— 如阿里百炼</li>
     *   <li><b>带 /v1 前缀</b>：{@code {baseUrl}/v1/chat/completions} —— 兼容 OpenAI 格式</li>
     * </ol>
     *
     * <p>URL 构建策略：</p>
     * <ul>
     *   <li>先尝试 {@code {baseUrl}/chat/completions}</li>
     *   <li>如果 baseUrl 中<b>不包含版本号</b>（如 /v1、/v2），则额外尝试
     *       {@code {baseUrl}/v1/chat/completions} 作为备选</li>
     *   <li>如果 baseUrl 已经包含版本号（如 {@code https://api.example.com/v2}），
     *       只尝试 {@code {baseUrl}/chat/completions}，不追加 /v1</li>
     * </ul>
     *
     * <p>使用 {@link LinkedHashSet} 去重并保持插入顺序（优先尝试不带版本号的路径）。</p>
     *
     * @param baseUrl Provider 配置的 baseUrl
     * @return 去重后的候选 URL 列表（不可变）
     */
    private List<String> buildConnectivityTestUrls(String baseUrl) {
        String normalizedBaseUrl = ApiPathResolver.stripTrailingSlashes(baseUrl);
        LinkedHashSet<String> candidateUrls = new LinkedHashSet<>();

        candidateUrls.add(normalizedBaseUrl + "/chat/completions");
        if (!ApiPathResolver.baseUrlContainsVersion(normalizedBaseUrl)) {
            candidateUrls.add(normalizedBaseUrl + "/v1/chat/completions");
        }

        return List.copyOf(candidateUrls);
    }

    /**
     * <h3>构建连通性测试的 HTTP 请求体</h3>
     *
     * <p>发送一个<b>最小化的聊天请求</b>来验证 Provider 是否可用：</p>
     * <ul>
     *   <li>model：使用 Provider 配置的模型名</li>
     *   <li>messages：单条用户消息，内容为 "Reply with OK only."（测试用，期望简短回复）</li>
     *   <li>max_tokens：设为 1，最小化 token 消耗（测试不需要实际生成内容）</li>
     * </ul>
     *
     * <p>注意：这个请求<b>不关注响应内容</b>，只要 HTTP 层面不报错就算连接成功。
     * 即使 API 返回 4xx（如参数错误），也能说明网络是通的。</p>
     *
     * @param model 模型名称
     * @return 请求体 Map（会被 RestClient 序列化为 JSON）
     */
    private Map<String, Object> buildConnectivityTestRequestBody(String model) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(Map.of(
                "role", "user",
                "content", "Reply with OK only."
        )));
        requestBody.put("max_tokens", 1);
        return requestBody;
    }

    /**
     * 对字符串进行 trim 处理，空字符串或仅空白 → null。
     * 用于统一处理用户输入的前后空白。
     */
    private String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 要求字符串非空（会先通过 trimOrNull 处理），否则抛出 BAD_REQUEST 异常。
     *
     * @param value     原始值
     * @param fieldName 字段名（用于异常消息）
     * @return trim 后的非空字符串
     */
    private String requireNonBlank(String value, String fieldName) {
        String normalized = trimOrNull(value);
        if (normalized == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, fieldName + " 不能为空");
        }
        return normalized;
    }

    /**
     * <h3>校验 Embedding 配置的合法性</h3>
     *
     * <p>校验规则（按优先级）：</p>
     * <ol>
     *   <li>如果不支持 Embedding，直接通过（无需校验）</li>
     *   <li>embeddingModel 不能为空</li>
     *   <li>embeddingModel 不能是聊天模型名（通过 {@link #looksLikeChatModel} 判断），
     *       如果看起来像聊天模型，给出该厂商推荐的 Embedding 模型名</li>
     *   <li>embeddingDimensions（向量维度）必须为正整数</li>
     * </ol>
     *
     * @param providerId         Provider ID（用于推荐正确的 Embedding 模型）
     * @param supportsEmbedding  embeddingModel 对应的 Provider 是否支持 Embedding
     * @param embeddingModel     某个 Provider 对应的 Embedding 模型名
     * @param embeddingDimensions 向量维度
     */
    private void validateEmbeddingConfig(
            String providerId,
            boolean supportsEmbedding,
            String embeddingModel,
            Integer embeddingDimensions) {
        String normalizedModel = trimOrNull(embeddingModel);
        if (!supportsEmbedding) {
            return;
        }
        if (normalizedModel == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "支持 Embedding 的 Provider 必须填写 embeddingModel");
        }
        if (looksLikeChatModel(normalizedModel)) {
            String recommendation = RECOMMENDED_EMBEDDING_MODELS.get(providerId.toLowerCase());
            String suffix = recommendation != null
                    ? "，推荐填写 " + recommendation
                    : "，请填写该厂商真实的 Embedding 模型名";
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "Embedding Model 不能填写聊天模型 '" + normalizedModel + "'" + suffix);
        }
        if (embeddingDimensions == null || embeddingDimensions <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "向量维度必须为正整数");
        }
    }

    /**
     * 解析 Embedding 维度：优先使用配置的值，其次使用全局默认值。
     *
     * @param configuredDimensions Provider 级别的 Embedding 维度配置（可为 null）
     * @return 有效的 Embedding 维度值
     */
    private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
        if (configuredDimensions != null && configuredDimensions > 0) {
            return configuredDimensions;
        }
        return properties.getEmbeddingDimensions();
    }

    /**
     * <h3>判断模型名是否"看起来像"聊天模型</h3>
     *
     * <p>通过模型名前缀来判断（不区分大小写）。这是启发式方法（heuristic），
     * 因为每种模型提供商的命名规则相对固定：</p>
     * <ul>
     *   <li>{@code glm-*} — 智谱 ChatGLM 系列</li>
     *   <li>{@code deepseek*} — DeepSeek 系列</li>
     *   <li>{@code kimi*} — Moonshot Kimi 系列</li>
     *   <li>{@code moonshot*} — Moonshot 系列</li>
     *   <li>{@code qwen*} — 阿里通义千问系列</li>
     *   <li>{@code ernie*} — 百度文心一言系列</li>
     * </ul>
     *
     * <p>这些前缀通常不会出现在 Embedding 模型名中（Embedding 模型名通常类似
     * {@code text-embedding-v3}、{@code embedding-3} 等）。</p>
     *
     * @param model 模型名称
     * @return true ，表示看起来像聊天模型（可能填错了）
     */
    private boolean looksLikeChatModel(String model) {
        String lower = model.toLowerCase();
        return lower.startsWith("glm-")
                || lower.startsWith("deepseek")
                || lower.startsWith("kimi")
                || lower.startsWith("moonshot")
                || lower.startsWith("qwen")
                || lower.startsWith("ernie");
    }

    /**
     * 根据 Provider ID 生成 .env 文件中的环境变量 Key。
     *
     * <p>规则：{@code "PROVIDER_" + 大写(ID) + "_API_KEY"}，其中 "-" 替换为 "_"。</p>
     * <p>示例：{@code "ali-bailian"} → {@code "PROVIDER_ALI_BAILIAN_API_KEY"}</p>
     */
    private String toEnvKey(String providerId) {
        return "PROVIDER_" + providerId.toUpperCase().replace("-", "_") + "_API_KEY";
    }

    // ===== Provider test logic (called under read lock) =====

    /**
     * <h3>Provider 连通性测试的核心逻辑</h3>
     *
     * <p>本方法是整个 Provider 测试功能的核心，通过发送一个最小化的 HTTP 聊天请求
     * 来验证 Provider 的可用性。</p>
     *
     * <h4>1. HTTP 客户端配置</h4>
     * <ul>
     *   <li><b>连接超时 5 秒</b>（{@code withConnectTimeout}）：TCP 握手超时
     *       —— 5 秒内连不上就判定为不可达</li>
     *   <li><b>读取超时 10 秒</b>（{@code withReadTimeout}）：等待响应的超时
     *       —— 连接成功后等待 HTTP 响应的最大时间</li>
     *   <li><b>InetAddressFilter（IP 地址过滤）</b>：控制允许连接哪些 IP
     *       —— 见下方详解</li>
     * </ul>
     *
     * <h4>2. InetAddressFilter 详解（SSRF 防护）</h4>
     * <p>构建了一个三层过滤链（OR 逻辑，任一匹配即放行）：</p>
     * <ol>
     *   <li><b>{@code InetAddressFilter.externalAddresses()}</b>：允许公网 IP
     *       —— 排除 10.0.0.0/8、172.16.0.0/12、192.168.0.0/16 等私有地址段</li>
     *   <li><b>{@code InetAddressFilter.adapt(InetAddress::isLoopbackAddress)}</b>：
     *       允许回环地址（127.0.0.1, ::1）—— 用于本地开发的 Provider</li>
     *   <li><b>{@code "198.18.0.0/15"}</b>：允许 198.18.0.0/15 网段
     *       —— 这是 RFC 2544 基准测试保留地址段，常用于内网测试环境</li>
     * </ol>
     *
     * <p><b>为什么需要 InetAddressFilter？</b></p>
     * <p>主要目的是防止 <b>SSRF（Server-Side Request Forgery）攻击</b>。
     * 如果用户恶意填写内网地址（如 {@code http://169.254.169.254/latest/meta-data/}
     * 或 {@code http://10.0.0.1/admin}），此过滤器可以阻止请求到达内网敏感服务。</p>
     *
     * <h4>3. 测试流程（多 URL 候选重试）</h4>
     * <p>并非所有的 LLM API 都使用相同的路径模式，因此构建多个候选 URL 逐个尝试：</p>
     * <pre>
     *   候选 1: {baseUrl}/chat/completions        （如阿里百炼格式）
     *   候选 2: {baseUrl}/v1/chat/completions     （如 OpenAI 兼容格式）
     * </pre>
     *
     * <p>遍历候选 URL，一旦任意一个返回了 HTTP 响应（无论状态码），
     * 都视为<b>连接成功</b>。只有所有候选 URL 都失败，才判定为连接失败。</p>
     *
     * <h4>4. 错误处理策略</h4>
     * <ul>
     *   <li><b>{@link RestClientResponseException}</b>：收到了 HTTP 响应但状态码异常
     *       （如 401 Unauthorized、404 Not Found）—— 记录详细的响应体（截断 200 字符），
     *       但<b>继续尝试下一个候选 URL</b>。因为 401 可能是 API Key 错误但
     *       网络是通的，404 可能是路径不对</li>
     *   <li><b>其他 {@link Exception}</b>：连接层面的错误（DNS 解析失败、
     *       连接超时、SSL 握手失败等）—— 记录异常类名和消息，继续尝试下一个 URL</li>
     * </ul>
     *
     * <h4>5. 返回值</h4>
     * <ul>
     *   <li>成功：{@code success=true, message="连接成功"}</li>
     *   <li>失败（所有 URL 都失败）：{@code success=false, message="连接失败: [最后一条错误信息]"}</li>
     *   <li>异常（测试框架本身出错）：{@code success=false, message="连接失败: [异常消息]"}</li>
     * </ul>
     *
     * @param config Provider 运行时配置（包含解密后的 API Key）
     * @param id     Provider ID（仅用于日志）
     * @return 测试结果
     */
    private ProviderTestResult doTestProvider(ProviderRuntimeConfig config, String id) {
        try {
            // ===== 步骤 1：构建带超时和 IP 过滤的 HTTP 客户端 =====
            HttpClientSettings settings = HttpClientSettings.defaults()
                    .withConnectTimeout(Duration.ofSeconds(5))
                    .withReadTimeout(Duration.ofSeconds(10))
                    .withInetAddressFilter(
                            InetAddressFilter.externalAddresses()
                                    .or(InetAddressFilter.adapt(InetAddress::isLoopbackAddress))
                                    .or("198.18.0.0/15"));

            RestClient restClient = RestClient.builder()
                    .defaultHeader("Authorization", "Bearer " + config.apiKey())
                    .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings))
                    .build();

            // ===== 步骤 2：构造最小化测试请求体 =====
            Map<String, Object> requestBody = buildConnectivityTestRequestBody(config.model());

            // ===== 步骤 3：构建候选 URL 列表 =====
            List<String> candidateUrls = buildConnectivityTestUrls(config.baseUrl());
            String lastFailureMessage = "Unknown error";

            // ===== 步骤 4：逐个尝试候选 URL =====
            for (String targetUrl : candidateUrls) {
                try {
                    // 发送 POST 请求，不关心响应体内容
                    restClient.post()
                            .uri(URI.create(targetUrl))
                            .body(requestBody)
                            .retrieve()
                            .toEntity(String.class);
                    // 收到响应即视为成功（无论是 200 还是 4xx/5xx）
                    log.info("Provider connectivity test succeeded: providerId={}, baseUrl={}, targetUrl={}, model={}",
                            id, config.baseUrl(), targetUrl, config.model());
                    return ProviderTestResult.builder()
                            .success(true)
                            .message("连接成功")
                            .model(config.model())
                            .build();
                } catch (RestClientResponseException e) {
                    // HTTP 层面收到了响应（如 401, 404），记录详情后继续尝试下一个 URL
                    String responseBody = abbreviate(e.getResponseBodyAsString());
                    lastFailureMessage = String.format(
                            "HTTP %s on %s, body=%s",
                            e.getStatusCode().value(),
                            targetUrl,
                            responseBody
                    );
                    log.warn(
                            "Provider connectivity test failed with response: providerId={}, baseUrl={}, targetUrl={}, model={}, status={}, body={}",
                            id,
                            config.baseUrl(),
                            targetUrl,
                            config.model(),
                            e.getStatusCode().value(),
                            responseBody,
                            e
                    );
                } catch (Exception e) {
                    // 连接层面错误（DNS、超时、SSL 等），记录后继续尝试下一个 URL
                    lastFailureMessage = String.format(
                            "%s on %s: %s",
                            e.getClass().getSimpleName(),
                            targetUrl,
                            e.getMessage()
                    );
                    log.warn(
                            "Provider connectivity test failed: providerId={}, baseUrl={}, targetUrl={}, model={}, error={}",
                            id,
                            config.baseUrl(),
                            targetUrl,
                            config.model(),
                            e.getMessage(),
                            e
                    );
                }
            }
            // ===== 步骤 5：所有 URL 都失败，返回最后一次的错误信息 =====
            return ProviderTestResult.builder()
                    .success(false)
                    .message("连接失败: " + lastFailureMessage)
                    .model(config.model())
                    .build();
        } catch (Exception e) {
            // ===== 步骤 6：测试框架本身异常（如 RestClient 构建失败） =====
            log.warn("Provider connectivity test setup failed: providerId={}, baseUrl={}, model={}, error={}",
                    id, config.baseUrl(), config.model(), e.getMessage(), e);
            return ProviderTestResult.builder()
                    .success(false)
                    .message("连接失败: " + e.getMessage())
                    .model(config.model())
                    .build();
        }
    }

    // ===== YAML text editing (preserves comments & formatting) =====
    // 以下方法使用 YamlTextEditor 进行文本级 YAML 编辑，
    // 而非使用 SnakeYAML 等库解析再写回，这样可以保留原有的注释和格式

    /**
     * 将 Provider 配置写入 YAML 文件（YAML 模式）。
     *
     * <p>使用 {@link YamlTextEditor#setBlock} 在 {@code app.ai.providers.<id>} 路径下
     * 写入/更新整个配置块。API Key 以环境变量引用的形式 {@code ${PROVIDER_XXX_API_KEY}}
     * 写入，实际值存储在 .env 文件中。</p>
     */
    private void writeProviderToYaml(String id, ProviderConfig config, String envKey) {
        mutateYamlText(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED, "写入 YAML 配置失败", editor -> {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            values.put("base-url", config.getBaseUrl());
            values.put("api-key", "${" + envKey + "}");
            values.put("model", config.getModel());
            if (config.getEmbeddingModel() != null) {
                values.put("embedding-model", config.getEmbeddingModel());
            }
            if (config.getEmbeddingDimensions() != null) {
                values.put("embedding-dimensions", config.getEmbeddingDimensions());
            }
            if (config.getTemperature() != null) {
                values.put("temperature", config.getTemperature());
            }
            editor.setBlock(new String[]{"app", "ai", "providers"}, id, values);
        });
    }

    /**
     * 从 YAML 文件中删除指定 Provider 的配置块。
     */
    private void removeProviderFromYaml(String id) {
        mutateYamlText(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED, "删除 YAML 配置失败", editor -> {
            editor.removeSection(new String[]{"app", "ai", "providers"}, id);
        });
    }

    /**
     * 将默认 Provider ID 写入 YAML 文件。
     * 同时清理旧的 module-defaults 配置（兼容旧版配置格式）。
     */
    private void writeDefaultProviderToYaml(String defaultProvider) {
        mutateYamlText(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED, "写入默认 Provider 配置失败", editor -> {
            editor.setScalar(new String[]{"app", "ai", "default-provider"}, defaultProvider);
            editor.removeSection(new String[]{"app", "ai"}, "module-defaults");
        });
    }

    /**
     * 将 ASR 配置写入 YAML 文件（{@code app.voice-interview.qwen.asr} 路径下）。
     */
    private void writeAsrConfigToYaml(VoiceInterviewProperties.AsrConfig asr) {
        mutateYamlText(ErrorCode.VOICE_CONFIG_WRITE_FAILED, "写入 ASR 配置失败", editor -> {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            values.put("url", asr.getUrl());
            values.put("model", asr.getModel());
            values.put("api-key", "${AI_BAILIAN_API_KEY}");
            values.put("language", asr.getLanguage());
            values.put("format", asr.getFormat());
            values.put("sample-rate", asr.getSampleRate());
            values.put("enable-turn-detection", asr.isEnableTurnDetection());
            values.put("turn-detection-type", asr.getTurnDetectionType());
            values.put("turn-detection-threshold", asr.getTurnDetectionThreshold());
            values.put("turn-detection-silence-duration-ms", asr.getTurnDetectionSilenceDurationMs());
            editor.setBlock(new String[]{"app", "voice-interview", "qwen"}, "asr", values);
        });
    }

    /**
     * 将 TTS 配置写入 YAML 文件（{@code app.voice-interview.qwen.tts} 路径下）。
     */
    private void writeTtsConfigToYaml(VoiceInterviewProperties.QwenTtsConfig tts) {
        mutateYamlText(ErrorCode.VOICE_CONFIG_WRITE_FAILED, "写入 TTS 配置失败", editor -> {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            values.put("model", tts.getModel());
            values.put("api-key", "${AI_BAILIAN_API_KEY}");
            values.put("voice", tts.getVoice());
            values.put("format", tts.getFormat());
            values.put("sample-rate", tts.getSampleRate());
            values.put("mode", tts.getMode());
            values.put("language-type", tts.getLanguageType());
            values.put("speech-rate", tts.getSpeechRate());
            values.put("volume", tts.getVolume());
            editor.setBlock(new String[]{"app", "voice-interview", "qwen"}, "tts", values);
        });
    }

    /**
     * <h3>YAML 文件变异的通用模板方法</h3>
     *
     * <p>封装了"读取文件 → 执行变更 → 写回文件"的标准流程：</p>
     * <ol>
     *   <li>读取 YAML 文件的所有行（如果不存在则创建空列表）</li>
     *   <li>创建 {@link YamlTextEditor} 实例</li>
     *   <li>执行传入的 mutator 逻辑</li>
     *   <li>将所有行连接为字符串，确保末尾有换行符</li>
     *   <li>写回文件</li>
     * </ol>
     *
     * <p>使用 {@link Consumer} 函数式接口作为 mutator，这是一种策略模式——
     * 调用者决定具体的编辑逻辑，本方法只提供文件读写的骨架。</p>
     *
     * @param errorCode    失败时抛出的错误码
     * @param errorMessage 失败时的错误消息前缀
     * @param mutator      具体的 YAML 编辑逻辑（Consumer 函数式接口）
     */
    private void mutateYamlText(ErrorCode errorCode, String errorMessage, Consumer<YamlTextEditor> mutator) {
        if (yamlPath == null || yamlPath.isBlank()) {
            log.warn("YAML path not configured, skip writing");
            return;
        }
        try {
            Path path = Path.of(yamlPath);
            List<String> lines;
            if (Files.exists(path)) {
                lines = new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
            } else {
                lines = new ArrayList<>();
            }

            YamlTextEditor editor = new YamlTextEditor(lines);
            mutator.accept(editor);

            String content = String.join("\n", editor.getLines());
            if (!content.endsWith("\n")) {
                content += "\n";
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException(errorCode, errorMessage + ": " + e.getMessage());
        }
    }

    // ===== .env file operations =====

    /**
     * 向 .env 文件写入或更新一个键值对。
     *
     * <p>处理三种情况：</p>
     * <ol>
     *   <li>文件不存在 → 创建新文件并写入</li>
     *   <li>Key 已存在 → 用正则替换整行（保留行位置）</li>
     *   <li>Key 不存在 → 追加到文件末尾</li>
     * </ol>
     *
     * <p>正则 {@code (?m)^KEY=.*} 中的 {@code (?m)} 是多行模式，
     * 使 {@code ^} 匹配每行的开头而非整个字符串的开头。</p>
     */
    private void writeEnvValue(String key, String value) {
        if (envPath == null || envPath.isBlank()) return;
        try {
            Path path = Path.of(envPath);
            if (!Files.exists(path)) {
                Files.writeString(path, key + "=" + value + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return;
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.contains(key + "=")) {
                content = content.replaceAll("(?m)^" + Pattern.quote(key) + "=.*",
                        Matcher.quoteReplacement(key + "=" + value));
            } else {
                if (!content.endsWith("\n")) {
                    content += "\n";
                }
                content += key + "=" + value + "\n";
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("写入 .env 失败: {}", e.getMessage());
        }
    }

    /**
     * 更新 .env 文件中的值（委托给 {@link #writeEnvValue}）。
     * 语义上等同于 writeEnvValue，命名区分是为了可读性。
     */
    private void updateEnvValue(String key, String value) {
        writeEnvValue(key, value);
    }

    /**
     * 从 .env 文件中删除指定 Key 的行。
     *
     * <p>正则 {@code (?m)^KEY=.*\R?} 匹配：</p>
     * <ul>
     *   <li>{@code (?m)} — 多行模式，{@code ^} 匹配行首</li>
     *   <li>{@code KEY=.*} — 匹配 Key 和值</li>
     *   <li>{@code \R?} — 可选的行分隔符（跨平台：\n、\r\n、\r），
     *       防止删除后留下空行</li>
     * </ul>
     */
    private void removeFromEnv(String key) {
        if (envPath == null || envPath.isBlank()) return;
        try {
            Path path = Path.of(envPath);
            if (!Files.exists(path)) return;
            String content = Files.readString(path, StandardCharsets.UTF_8);
            content = content.replaceAll("(?m)^" + Pattern.quote(key) + "=.*\\R?", "");
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("删除 .env 条目失败: {}", e.getMessage());
        }
    }

    // ===== YamlTextEditor: text-based YAML editing that preserves comments & formatting =====

    /**
     * <h3>YAML 文本编辑器 —— 保留注释和格式的 YAML 编辑</h3>
     *
     * <p>与传统的 YAML 解析库（如 SnakeYAML）不同，本编辑器在<b>文本行级别</b>操作，
     * 而非解析为对象树再序列化。这样做的好处是：</p>
     * <ul>
     *   <li><b>保留注释</b>：SnakeYAML 解析会丢失所有注释，本编辑器不会</li>
     *   <li><b>保留格式</b>：空行、缩进风格、key 的顺序等原始格式完全保留</li>
     *   <li><b>保留非目标内容</b>：只修改目标 key 的值，其他配置（包括不在
     *       {@code app.ai.providers} 路径下的）完全不动</li>
     * </ul>
     *
     * <p><b>代价</b>：只能理解简单的层级结构（基于缩进），无法处理复杂的 YAML 特性
     * （如多行字符串、锚点/别名、flow style 等）。但对于本应用的配置文件格式
     * （简单的 key-value 嵌套），完全够用。</p>
     *
     * <h4>核心概念</h4>
     * <ul>
     *   <li><b>缩进规则</b>：每层嵌套对应 2 个空格的缩进（这是本应用的约定）</li>
     *   <li><b>path</b>：用字符串数组表示 YAML 路径，
     *       如 {@code ["app", "ai", "providers"]} 对应：
     *       <pre>
     * app:
     *   ai:
     *     providers:</pre></li>
     *   <li><b>setScalar</b>：设置标量值（如 {@code default-provider: alibaba}）</li>
     *   <li><b>setBlock</b>：设置一个配置块（如整个 Provider 的多个子属性）</li>
     *   <li><b>removeSection</b>：删除一个配置段及其所有子行</li>
     * </ul>
     *
     * <h4>查找算法</h4>
     * <p>所有查找都基于<b>缩进级别</b>：遍历行列表，跳过空行和注释行，
     * 比较每行的缩进深度来确定层级关系。</p>
     * <ul>
     *   <li>{@code findKey}：在指定范围内查找特定缩进级别的 key</li>
     *   <li>{@code findSectionEnd}：找到当前段落的结束位置
     *       （下一个缩进 ≤ parentIndent 的行）</li>
     *   <li>{@code ensureParents}：确保路径上的所有父级 key 都存在，不存在的自动创建</li>
     *   <li>{@code navigateTo}：导航到指定路径，返回该路径最后一行的下一行索引</li>
     * </ul>
     */
    static class YamlTextEditor {
        /**
         * YAML 文件的每一行（可变列表，编辑操作直接修改此列表）。
         */
        private final List<String> lines;

        /**
         * @param lines YAML 文件的行列表（会被复制，不影响原始列表）
         */
        YamlTextEditor(List<String> lines) {
            this.lines = new ArrayList<>(lines);
        }

        /**
         * 获取编辑后的行列表。
         */
        List<String> getLines() {
            return lines;
        }

        /**
         * <h4>设置标量值（如 {@code key: value}）</h4>
         *
         * <p>流程：</p>
         * <ol>
         *   <li>确保父路径存在（{@code ensureParents}）</li>
         *   <li>根据路径深度计算缩进（每层 2 空格）</li>
         *   <li>在当前缩进级别查找目标 key</li>
         *   <li>找到了 → 替换行内容；未找到 → 在段落末尾插入新行</li>
         * </ol>
         *
         * @param path  YAML 路径（如 {@code ["app", "ai", "default-provider"]}）
         * @param value 标量值（会被 {@link #formatValue} 格式化）
         */
        void setScalar(String[] path, String value) {
            int searchFrom = path.length > 1
                    ? ensureParents(Arrays.copyOf(path, path.length - 1))
                    : 0;
            int indent = (path.length - 1) * 2;
            String key = path[path.length - 1];
            String newLine = " ".repeat(indent) + key + ": " + value;

            int found = findKey(key, indent, searchFrom);
            if (found >= 0) {
                lines.set(found, newLine);
            } else {
                int parentIndent = indent >= 2 ? indent - 2 : -1;
                int insertPos = findSectionEnd(searchFrom, parentIndent);
                lines.add(insertPos, newLine);
            }
        }

        /**
         * <h4>设置一个配置块（多行嵌套属性）</h4>
         *
         * <p>例如，在 {@code app.ai.providers} 下写入一个 Provider 的完整配置块：</p>
         * <pre>
         *   alibaba:
         *     base-url: https://...
         *     model: qwen-turbo
         *     ...</pre>
         *
         * <p>流程：</p>
         * <ol>
         *   <li>确保父路径存在，找到块的插入/更新位置</li>
         *   <li>查找目标 blockKey（如 Provider ID），找不到则创建空块</li>
         *   <li>确定块的结束位置（{@code findSectionEnd}）</li>
         *   <li>在块的范围内，逐个更新或插入子属性</li>
         *   <li>已存在的属性原地替换，新属性追加到块末尾</li>
         * </ol>
         *
         * @param parentPath 父路径（如 {@code ["app", "ai", "providers"]}）
         * @param blockKey   块名称（如 Provider ID "alibaba"）
         * @param values     块内的键值对（有序 Map，按插入顺序写入）
         */
        void setBlock(String[] parentPath, String blockKey, LinkedHashMap<String, Object> values) {
            int parentSearchFrom = ensureParents(parentPath);
            int blockIndent = parentPath.length * 2;
            int valueIndent = blockIndent + 2;

            // 查找或创建配置块
            int blockLine = findKey(blockKey, blockIndent, parentSearchFrom);
            if (blockLine < 0) {
                int parentEnd = parentPath.length >= 1 ? blockIndent - 2 : -1;
                int insertPos = findSectionEnd(parentSearchFrom, parentEnd);
                lines.add(insertPos, " ".repeat(blockIndent) + blockKey + ":");
                blockLine = insertPos;
            }

            // 确定块的结束位置（下一个缩进 ≤ blockIndent 的行）
            int blockEnd = findSectionEnd(blockLine + 1, blockIndent);

            // 在块范围内逐个更新/插入属性
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                String valueLine = " ".repeat(valueIndent) + entry.getKey() + ": " + formatValue(entry.getValue());
                int existing = findKeyInRange(entry.getKey(), valueIndent, blockLine + 1, blockEnd);
                if (existing >= 0) {
                    lines.set(existing, valueLine);
                } else {
                    lines.add(blockEnd, valueLine);
                    blockEnd++;
                }
            }
        }

        /**
         * <h4>删除一个配置段及其所有子行</h4>
         *
         * <p>流程：</p>
         * <ol>
         *   <li>导航到父路径</li>
         *   <li>查找目标 sectionKey</li>
         *   <li>确定该段的结束位置（下一个缩进 ≤ sectionIndent 的行）</li>
         *   <li>从后向前删除（避免索引变化问题）</li>
         * </ol>
         */
        void removeSection(String[] parentPath, String sectionKey) {
            int parentSearchFrom = navigateTo(parentPath);
            if (parentSearchFrom < 0) return;

            int sectionIndent = parentPath.length * 2;
            int sectionLine = findKey(sectionKey, sectionIndent, parentSearchFrom);
            if (sectionLine < 0) return;

            int endLine = sectionLine + 1;
            while (endLine < lines.size()) {
                String line = lines.get(endLine);
                if (line.isBlank()) { endLine++; continue; }
                if (indentOf(line) <= sectionIndent) break;
                endLine++;
            }

            for (int i = endLine - 1; i >= sectionLine; i--) {
                lines.remove(i);
            }
        }

        /**
         * <h4>确保父路径上的所有 key 都存在</h4>
         *
         * <p>例如，要写入 {@code app.ai.providers.alibaba}，需要确保
         * {@code app}、{@code ai}、{@code providers} 这三层 key 都存在。
         * 不存在的自动创建（只创建 key: 行，不创建子属性）。</p>
         *
         * @param path 需要确保存在的父路径
         * @return 路径最后一级的下一行索引（用于后续查找）
         */
        private int ensureParents(String[] path) {
            int searchFrom = 0;
            for (int i = 0; i < path.length; i++) {
                int indent = i * 2;
                int found = findKey(path[i], indent, searchFrom);
                if (found < 0) {
                    int parentIndent = i > 0 ? indent - 2 : -1;
                    int insertPos = findSectionEnd(searchFrom, parentIndent);
                    lines.add(insertPos, " ".repeat(indent) + path[i] + ":");
                    searchFrom = insertPos + 1;
                } else {
                    searchFrom = found + 1;
                }
            }
            return searchFrom;
        }

        /**
         * <h4>导航到指定路径</h4>
         *
         * <p>与 {@code ensureParents} 不同，此方法不创建不存在的 key——
         * 如果路径中任何一级不存在，返回 -1。</p>
         *
         * @param path 需要导航到的路径
         * @return 路径最后一行的下一行索引，或 -1（路径不存在）
         */
        private int navigateTo(String[] path) {
            int searchFrom = 0;
            for (int i = 0; i < path.length; i++) {
                int indent = i * 2;
                int found = findKey(path[i], indent, searchFrom);
                if (found < 0) return -1;
                searchFrom = found + 1;
            }
            return searchFrom;
        }

        /**
         * <h4>在行列表中查找指定缩进级别的 Key</h4>
         *
         * <p>从 searchFrom 开始向后遍历，匹配规则：</p>
         * <ul>
         *   <li>跳过空行和注释行（以 # 开头）</li>
         *   <li>匹配以 {@code "  " * indent + key + ":"} 开头的行</li>
         *   <li>遇到缩进小于 target indent 的行时停止（说明已经离开了当前层级）</li>
         * </ul>
         *
         * @param key        目标 Key
         * @param indent     目标缩进级别（空格数）
         * @param searchFrom 开始搜索的行索引
         * @return 匹配行的索引，或 -1（未找到）
         */
        private int findKey(String key, int indent, int searchFrom) {
            String prefix = " ".repeat(indent) + key + ":";
            for (int i = searchFrom; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank() || line.trim().startsWith("#")) continue;
                if (line.startsWith(prefix)) return i;
                if (indentOf(line) < indent) break;
            }
            return -1;
        }

        /**
         * <h4>在指定范围内查找 Key</h4>
         * 与 {@link #findKey} 类似，但限制在 [start, end) 范围内搜索。
         */
        private int findKeyInRange(String key, int indent, int start, int end) {
            String prefix = " ".repeat(indent) + key + ":";
            for (int i = start; i < end && i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank() || line.trim().startsWith("#")) continue;
                if (line.startsWith(prefix)) return i;
                if (indentOf(line) < indent) break;
            }
            return -1;
        }

        /**
         * <h4>找到当前段落的结束位置</h4>
         *
         * <p>从 searchFrom 开始向后查找，直到找到缩进 ≤ parentIndent 的行，
         * 该行就是当前段落的结束边界。</p>
         *
         * <p>例如：</p>
         * <pre>
         * app:              ← parentIndent = -1
         *   ai:             ← parentIndent = 0
         *     providers:    ← parentIndent = 2，findSectionEnd 从这里开始
         *       alibaba:    ← indent=4 > 2，属于段落内
         *         ...       ← indent=6 > 2，属于段落内
         *   other:          ← indent=2 ≤ 2，段落结束</pre>
         *
         * @param searchFrom   开始搜索的行索引
         * @param parentIndent 父级缩进级别
         * @return 段落结束位置的行索引（指向下一个同级别或更高级别的 key，或文件末尾）
         */
        private int findSectionEnd(int searchFrom, int parentIndent) {
            for (int i = searchFrom; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank() || line.trim().startsWith("#")) continue;
                if (indentOf(line) <= parentIndent) return i;
            }
            return lines.size();
        }

        /**
         * 计算一行的缩进空格数。
         * 只统计行首连续空格的数量。
         */
        private int indentOf(String line) {
            int count = 0;
            while (count < line.length() && line.charAt(count) == ' ') count++;
            return count;
        }

        /**
         * 将值格式化为 YAML 兼容的字符串。
         * Boolean → true/false，Number → 数字字符串，其他 → toString()。
         */
        private String formatValue(Object value) {
            if (value instanceof Boolean b) return b.toString();
            if (value instanceof Number n) return n.toString();
            return value.toString();
        }
    }

    /**
     * <h3>Provider 运行时配置（内部 Record）</h3>
     *
     * <p>用于在方法间传递完整的 Provider 配置信息（包含解密后的明文 API Key）。
     * 主要用于连通性测试。使用 Java Record 保证了不可变性和简洁性。</p>
     *
     * <p><b>注意：</b>此 Record 包含明文 API Key，仅在服务内部临时使用，
     * 绝不应返回给 Controller 层或序列化为 JSON 响应。</p>
     *
     * @param baseUrl            Provider API 的基础 URL
     * @param apiKey             解密后的明文 API Key（<b>敏感信息，不可对外暴露</b>）
     * @param model              聊天模型名
     * @param embeddingModel     Embedding 模型名（可为 null）
     * @param embeddingDimensions Embedding 向量维度（可为 null）
     * @param supportsEmbedding  是否支持 Embedding
     * @param temperature        生成温度参数（可为 null）
     */
    private record ProviderRuntimeConfig(
            String baseUrl,
            String apiKey,
            String model,
            String embeddingModel,
            Integer embeddingDimensions,
            boolean supportsEmbedding,
            Double temperature
    ) {
    }
}