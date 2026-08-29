package com.ywy.interviewagentapplication.common.ai;

import com.openai.client.OpenAIClient;
import com.ywy.interviewagentapplication.common.config.LlmProviderProperties;
import com.ywy.interviewagentapplication.common.config.LlmProviderProperties.AdvisorConfig;
import com.ywy.interviewagentapplication.common.config.LlmProviderProperties.ProviderConfig;
import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import com.ywy.interviewagentapplication.modules.llmprovider.model.LlmGlobalSettingEntity;
import com.ywy.interviewagentapplication.modules.llmprovider.model.LlmProviderEntity;
import com.ywy.interviewagentapplication.modules.llmprovider.repository.LlmGlobalSettingRepository;
import com.ywy.interviewagentapplication.modules.llmprovider.repository.LlmProviderRepository;
import com.ywy.interviewagentapplication.modules.llmprovider.service.ApiKeyEncryptionService;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM Provider 注册中心，是整个 AI 能力层的中枢
 * 所有业务模块获取 ChatClient / EmbeddingModel 都通过这个入口，而不是直接使用 Spring AI 的自动配置。
 * 设计原因：
 * - Spring Ai 的自动配置假设只有一个 Provider（一套 API Key / Base URL）
 * - 本项目支持同时配置 5+ Provider（DashScope / DeepSeek / Kimi / GLM / 本地 LM Studio）
 * - 用户可以在设置页运行时切换默认 Provider，而自动配置无法支持
 * 核心功能：
 * 1. 管理多个 LLM Provider 的 ChatClient / EmbeddingModel 生命周期
 * 2. 内存缓存（ConcurrentHashMap）：首次访问时创建，后续复用
 * 3. 支持运行时清空缓存（reload()）：Provider 配置变更后重新创建
 * 设计要点：
 * - 键缓存策略：providerId（"dashscope"）、providerId+":plain"（结构化输出）、providerId+":voice"（语音面试）
 * - Provider 配置加载优先级：数据库 > application.yml
 * - Embedding 模型安全检查：拒绝将聊天模型（qwen/gpt等）当作 Embedding 模型使用
 * - Advisor 链：ToolCall → ChatMemory → SimpleLogger → SafeGuard
 * - 三种 ChatClient 类型
 * 1. getChatClient：SkillsTool + 完整 Advisors 链，用于通用场景
 * 2. getPlainChatClient：无 Tools（避免混入工具调用消息），用于结构化输出
 * 3. getVoiceChatClient：SkillsTool + 流式 ToolCall + 无 Memory，用于语音面试
 */
@Component
@Slf4j
public class LlmProviderRegistry {

    private final LlmProviderProperties properties;
    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();
    private final Map<String, OpenAiChatModel> chatModelCache = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingModel> embeddingModelCache = new ConcurrentHashMap<>();
    private final LlmProviderRepository providerRepository;
    private final LlmGlobalSettingRepository globalSettingRepository;
    private final ApiKeyEncryptionService encryptionService;

    private final ToolCallingManager toolCallingManager;
    private final ObservationRegistry observationRegistry;
    private final ToolCallback interviewSkillsToolCallback;
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
    public LlmProviderRegistry(
            LlmProviderProperties properties,
            LlmProviderRepository providerRepository,
            LlmGlobalSettingRepository globalSettingRepository,
            ApiKeyEncryptionService encryptionService,
            @Autowired(required = false) ToolCallingManager toolCallingManager,
            @Autowired(required = false) ObservationRegistry observationRegistry,
            @Autowired(required = false) @Qualifier("interviewSkillsToolCallback") ToolCallback interviewSkillsToolCallback) {
        this.properties = properties;
        this.providerRepository = providerRepository;
        this.globalSettingRepository = globalSettingRepository;
        this.encryptionService = encryptionService;
        this.toolCallingManager = toolCallingManager;
        this.observationRegistry = observationRegistry;
        this.interviewSkillsToolCallback = interviewSkillsToolCallback;
    }

    /**
     * <h3>简化构造函数（YAML 文件模式，用于兼容旧版）</h3>
     * <p>当没有数据库依赖时使用此构造函数。此时 providerRepository、globalSettingRepository、
     * encryptionService 均为 null，系统回退到 YAML + .env 文件存储模式。</p>
     * <p>通过 {@code this(...)} 调用完整构造函数，将三个数据库依赖传为 null。</p>
     */
    public LlmProviderRegistry(
            LlmProviderProperties properties,
            ToolCallingManager toolCallingManager,
            ObservationRegistry observationRegistry,
            ToolCallback interviewSkillsToolCallback) {
        this(properties, null, null, null, toolCallingManager, observationRegistry, interviewSkillsToolCallback);
    }

    /**
     * 通过指定的 Provider ID 获取 ChatClient（缓存未命中时则创建通用型 ChatClient），并放入缓存集合。
     */
    public ChatClient getChatClient(String providerId) {
        return clientCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new client for provider: {}", id);
            return createChatClient(id);
        });
    }

    /**
     * 通过默认的 ProviderId 获取 ChatClient。
     */
    public ChatClient getDefaultChatClient() {
        return getChatClient(resolveDefaultChatProviderId());
    }

    /**
     * 通过指定的 Provider ID 获取 ChatClient，如果缓存未命中则使用默认 Provider ID 的 ChatClient。
     */
    public ChatClient getChatClientOrDefault(String providerId) {
        return getChatClient(resolveProviderId(providerId));
    }

    /**
     * 通过指定的 Provider ID 获取不带 SkillsTool 的纯文本 ChatClient，用于结构化输出场景（简历评分等）。
     * 如果 ChatClient 缓存未命中，则先创建并放入缓存集合，然后再返回。
     * 结构化输出场景要求模型一次性返回可解析 JSON，不应混入工具调用消息。
     */
    public ChatClient getPlainChatClient(String providerId) {
        String id = resolveProviderId(providerId);
        return clientCache.computeIfAbsent(id + ":plain", key -> createPlainChatClient(id));
    }

    /**
     * 通过默认的 Provider ID 获取不带 SkillsTool 的纯文本 ChatClient，用于结构化输出场景（简历评分等）。
     * 如果 ChatClient 缓存未命中，则先创建并放入缓存集合，然后再返回。
     */
    public ChatClient getPlainChatClient() {
        return getPlainChatClient(resolveDefaultChatProviderId());
    }

    /**
     * 通过指定的 Provider ID 获取语音面试专用 ChatClient：SkillsTool + ToolCallAdvisor（流式）。
     * 不加 Memory Advisor，手动管理对话历史。
     * 如果 ChatClient 缓存未命中，则先创建并放入缓存集合，然后再返回。
     */
    public ChatClient getVoiceChatClient(String providerId) {
        String id = resolveProviderId(providerId);
        return clientCache.computeIfAbsent(id + ":voice", key -> createVoiceChatClient(id));
    }

    /**
     * 清空缓存，只有再次获取某个 Provider ID对应的 chatClient/embeddingModel/OpenAiChatModel 时，才重新动态加载相应的实例。
     */
    public void reload() {
        int size = clientCache.size() + chatModelCache.size() + embeddingModelCache.size();
        clientCache.clear();
        chatModelCache.clear();
        embeddingModelCache.clear();
        log.info("[LlmProviderRegistry] Cache cleared ({} entries). Next access will re-create clients.", size);
    }

    /**
     * 通过指定的 Provider ID 获取 EmbeddingModel。
     * 如果 embeddingModel 缓存未命中，则先创建并放入缓存集合，然后再返回。
     */
    public EmbeddingModel getEmbeddingModel(String providerId) {
        return embeddingModelCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new embedding model for provider: {}", id);
            return createEmbeddingModel(id);
        });
    }

    /**
     * 优先从数据库读取，若数据库不存在（特指未使用数据库模式构造），则从属性类中读取。
     * <p>首先获取默认 EmbeddingModel 对应的 Provider ID，如果没有默认 EmbeddingModel 则获取默认 chatModel 对应的 Provider ID。
     * <p>根据获取到的 Provider ID 去查找 embeddingModel，如果缓存未命中则先使用此 Provider ID 创建 embeddingModel（前提是该 Provider 支持 Embedding，否则直接抛出业务异常） 并放入缓存集合，然后再返回。
     */
    public EmbeddingModel getDefaultEmbeddingModel() {
        return getEmbeddingModel(resolveDefaultEmbeddingProviderId());
    }

    // ===== ChatClient 构建（三种类型） =====

    /**
     * 1. 获取指定 Provider ID 对应的 OpenAiChatModel，若缓存未命中，则根据该 ID 创建一个 OpenAiChatModel，并放入缓存集合。
     * 2. 根据获取到的 OpenAiChatModel 创建 ChatClient，并配置：SkillsTool【若外部未配置，则默认关闭工具调用历史记录】 + 完整 Advisor 链【工具调用，对话记忆（默认关闭），日志调试（默认关闭），敏感词拦截】。
     * @param providerId 指定的 Provider ID
     * @return 通用 ChatClient（注意，该方法仅创建 chatClient，并不会将其加入缓存集合）
     */
    private ChatClient createChatClient(String providerId) {
        OpenAiChatModel chatModel = getChatModel(providerId);

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (interviewSkillsToolCallback != null) {
            builder.defaultTools(interviewSkillsToolCallback);
        }
        List<Advisor> advisors = buildDefaultAdvisors(providerId);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors);
            log.info("[LlmProviderRegistry] Applied {} advisors for provider {}", advisors.size(), providerId);
        }

        return builder.build();
    }

    /**
     * 1. 获取指定 Provider ID 对应的 OpenAiChatModel，若缓存未命中，则根据该 ID 创建一个 OpenAiChatModel，并放入缓存集合。
     * 2. 根据获取到的 OpenAiChatModel 创建 ChatClient，并配置：敏感词拦截 Advisor。
     * @param providerId 指定的 Provider ID
     * @return 纯文本 ChatClient（注意，该方法仅创建 chatClient，并不会将其加入缓存集合）
     */
    private ChatClient createPlainChatClient(String providerId) {
        OpenAiChatModel chatModel = getChatModel(providerId);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        buildSafeGuardAdvisor().ifPresent(advisor -> builder.defaultAdvisors(List.of(advisor)));
        log.info("[LlmProviderRegistry] Created plain ChatClient (no tools) for {}", providerId);
        return builder.build();
    }

    /**
     * 1. 获取指定 Provider ID 对应的 OpenAiChatModel，若缓存未命中，则根据该 ID 创建一个 OpenAiChatModel，并放入缓存集合。
     * 2. 根据获取到的 OpenAiChatModel 创建 ChatClient，并配置：SkillsTool【关闭工具调用历史记录】 + Advisor 链【工具调用，敏感词拦截】
     * @param providerId 指定的 Provider ID
     * @return 语音面试 ChatClient（注意，该方法仅创建 chatClient，并不会将其加入缓存集合）
     */
    private ChatClient createVoiceChatClient(String providerId) {
        OpenAiChatModel chatModel = getChatModel(providerId);

        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (interviewSkillsToolCallback != null) {
            builder.defaultTools(interviewSkillsToolCallback);
        }
        List<Advisor> advisors = new ArrayList<>();
        if (toolCallingManager != null) {
            advisors.add(buildToolCallAdvisor(true));
        }
        buildSafeGuardAdvisor().ifPresent(advisors::add);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors);
        }
        log.info("[LlmProviderRegistry] Created voice ChatClient (SkillsTool + streaming ToolCall) for {}", providerId);
        return builder.build();
    }

    // ===== OpenAiChatModel / EmbeddingModel 底层构建 =====

    /**
     * 获取指定 Provider ID 对应的 OpenAiChatModel。
     * 若缓存未命中，则根据该 ID 创建一个 OpenAiChatModel 并放入缓存集合，然后再返回。
     */
    private OpenAiChatModel getChatModel(String providerId) {
        return chatModelCache.computeIfAbsent(providerId, id -> {
            log.info("[LlmProviderRegistry] Creating new ChatModel for provider: {}", id);
            return buildChatModel(id);
        });
    }

    /**
     * 根据指定 Provider ID 获取对应的 ProviderSnapshot，创建对应的 OpenAiChatModel（注意，该方法仅创建 OpenAiChatModel，并不会将其加入缓存集合）。
     * 兼容 OpenAI API 的大模型均支持通过 OpenAiChatModel 进行通信。
     */
    private OpenAiChatModel buildChatModel(String providerId) {
        ProviderSnapshot config = loadProviderOrThrow(providerId);
        log.info("[LlmProviderRegistry] Building ChatModel - Provider: {}, BaseUrl: {}, Model: {}",
                providerId, config.baseUrl(), config.model());
        // 通过 ApiPathResolver 创建底层 HTTP 客户端
        OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(config.baseUrl(), config.apiKey());

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.model())
                .temperature(config.temperature() != null ? config.temperature() : 0.2)
                .build();

        return OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .openAiClientAsync(openAiClient.async())
                .options(options)
                .observationRegistry(observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP)
                .build();
    }

    /**
     * 根据指定 Provider ID 获取对应的 ProviderSnapshot，创建对应的 EmbeddingModel（注意，该方法仅创建 EmbeddingModel，并不会将其加入缓存集合）。
     * <p>
     * 安全检查：指定 ID 的 provider 必须支持 Embedding，且配对的 Embedding Model 不能是 LLM Model。否则抛出业务异常，阻止创建。
     */
    private EmbeddingModel createEmbeddingModel(String providerId) {
        ProviderSnapshot config = loadProviderOrThrow(providerId);
        // 安全检查 1：Provider 必须支持 Embedding
        if (!config.supportsEmbedding() || isBlank(config.embeddingModel())) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                    "Provider '" + providerId + "' 未配置可用的 Embedding 模型，无法执行知识库向量化");
        }
        // 安全检查 2：拒绝将聊天模型当作 Embedding 模型
        if (looksLikeChatModel(config.embeddingModel())) {
            String recommendation = RECOMMENDED_EMBEDDING_MODELS.get(providerId.toLowerCase());
            String suffix = recommendation != null
                    ? "，推荐填写 " + recommendation
                    : "，请填写该厂商真实的 Embedding 模型名";
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                    "Provider '" + providerId + "' 的 Embedding Model 配成了聊天模型 '"
                            + config.embeddingModel() + "'" + suffix);
        }
        log.info("[LlmProviderRegistry] Building EmbeddingModel - Provider: {}, BaseUrl: {}, Model: {}",
                providerId, config.baseUrl(), config.embeddingModel());

        OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(config.baseUrl(), config.apiKey());
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(config.embeddingModel())
                .dimensions(resolveEmbeddingDimensions(config.embeddingDimensions()))
                .build();

        return OpenAiEmbeddingModel.builder()
                .openAiClient(openAiClient)
                .metadataMode(MetadataMode.EMBED)
                .options(options)
                .observationRegistry(observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP)
                .build();
    }

    // ===== Advisor 拦截器链 =====

    /**
     * 构建完整的 Advisor 拦截器链：
     * 工具调用（默认关闭历史调用记录），对话记忆（默认关闭），日志调试（默认关闭），敏感词拦截
     */
    private List<Advisor> buildDefaultAdvisors(String providerId) {
        AdvisorConfig config = properties.getAdvisors();
        if (config == null || !config.isEnabled()) {
            return List.of();
        }

        List<Advisor> advisors = new ArrayList<>();
        // 1. ToolCallAdvisor：处理 LLM 的函数调用请求
        if (config.isToolCallEnabled()) {
            if (toolCallingManager != null) {
                // 默认关闭 工具调用的历史记录功能
                advisors.add(buildToolCallAdvisor(config.isToolCallConversationHistoryEnabled()));
            } else {
                log.warn("[LlmProviderRegistry] ToolCallAdvisor skipped: ToolCallingManager unavailable, provider={}", providerId);
            }
        }

        // 2. MessageChatMemoryAdvisor：维护对话记忆（默认关闭）
        if (config.isMessageChatMemoryEnabled()) {
            int maxMessages = Math.max(20, config.getMessageChatMemoryMaxMessages());
            MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(
                    MessageWindowChatMemory.builder()
                            .maxMessages(maxMessages)
                            .build()
            ).build();
            advisors.add(memoryAdvisor);
        }

        // 3. SimpleLoggerAdvisor：调试日志（默认关闭）
        if (config.isSimpleLoggerEnabled()) {
            advisors.add(new SimpleLoggerAdvisor());
        }

        // 4. SafeGuardAdvisor：敏感词拦截。最后一道防线
        buildSafeGuardAdvisor().ifPresent(advisors::add);

        return advisors;
    }

    /**
     * 构建工具调用 Advisor，根据 conversationHistoryEnabled 决定是否关闭历史调用记录功能。
     */
    private ToolCallingAdvisor buildToolCallAdvisor(boolean conversationHistoryEnabled) {
        ToolCallingAdvisor.Builder<?> builder = ToolCallingAdvisor.builder();
        builder.toolCallingManager(toolCallingManager)
                .conversationHistoryEnabled(conversationHistoryEnabled);
        return builder.build();
        /*
        return ToolCallingAdvisor.builder()
                .toolCallingManager(toolCallingManager)
                .conversationHistoryEnabled(conversationHistoryEnabled)
                .build(); 为什么链式调用报错？
         */
    }

    /**
     * 构建敏感词拦截 Advisor。
     */
    private Optional<SafeGuardAdvisor> buildSafeGuardAdvisor() {
        AdvisorConfig config = properties.getAdvisors();
        if (config == null || !config.isSafeguardEnabled()) {
            return Optional.empty();
        }
        SafeGuardAdvisor advisor = SafeGuardAdvisor.builder()
                .sensitiveWords(config.getSafeguardWords())
                .failureResponse("抱歉，我只能协助面试相关的任务。")
                .order(100)
                .build();
        return Optional.of(advisor);
    }

    /** 用于确保 providerId 格式正确。
     * @param providerId    指定的 providerId
     * @return 优化格式后的 providerId。
     */
    private String resolveProviderId(String providerId) {
        if (providerId == null || providerId.isBlank() || "default".equalsIgnoreCase(providerId.trim())) {
            return resolveDefaultChatProviderId();
        }
        return providerId;
    }

    /**
     * 优先使用数据库中存储的配置，
     * 若数据库不存在（特指未使用数据库模式构造），则退回使用属性类配置。
     * 返回默认 chatModel 对应的 Provider ID。
     */
    private String resolveDefaultChatProviderId() {
        if (globalSettingRepository == null) {
            return properties.getDefaultProvider();
        }
        return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
                .map(LlmGlobalSettingEntity::getDefaultChatProviderId)
                .filter(id -> !isBlank(id))
                .orElse(properties.getDefaultProvider());
    }

    /**
     * 优先从数据库读取，若数据库不存在（特指未使用数据库模式构造），则从属性类中读取。
     * 返回默认 embeddingModel 对应的 Provider ID，如果没有则返回默认 chatModel 对应的 Provider ID。
     */
    private String resolveDefaultEmbeddingProviderId() {
        if (globalSettingRepository == null) {
            return !isBlank(properties.getDefaultEmbeddingProvider())
                    ? properties.getDefaultEmbeddingProvider()
                    : properties.getDefaultProvider();
        }
        return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
                .map(LlmGlobalSettingEntity::getDefaultEmbeddingProviderId)
                .filter(id -> !isBlank(id))
                .orElseGet(() -> !isBlank(properties.getDefaultEmbeddingProvider())
                        ? properties.getDefaultEmbeddingProvider()
                        : properties.getDefaultProvider());
    }

    /**
     * 根据指定 Provider ID 从数据库或配置类中加载指定 LLM 供应商的信息。
     * <p>
     * 优先从数据库读取（并校验启用状态，解密 API Key），若数据库不存在（特指未使用数据库模式构造），则从属性类中读取。
     * @return 包含完整连接信息的 ProviderSnapshot
     * @throws IllegalArgumentException 当提供商不存在或已禁用时抛出
     */
    private ProviderSnapshot loadProviderOrThrow(String providerId) {
        if (providerRepository == null) {
            return loadProviderFromPropertiesOrThrow(providerId);
        }
        LlmProviderEntity entity = providerRepository.findById(providerId)
                .filter(LlmProviderEntity::isEnabled)
                .orElseThrow(() -> new IllegalArgumentException("Unknown LLM provider: " + providerId));
        return new ProviderSnapshot(
                entity.getId(),
                entity.getBaseUrl(),
                encryptionService.decrypt(entity.getApiKeyNonce(), entity.getApiKeyCiphertext()),
                entity.getModel(),
                entity.getEmbeddingModel(),
                entity.getEmbeddingDimensions(),
                entity.isSupportsEmbedding(),
                entity.getTemperature()
        );
    }

    /**
     * 根据指定 Provider ID 从属性类中加载供应商的信息（用于无数据库场景）。
     * <p>
     * 根据供应商是否支持 embedding，是否存在 embeddingModel 决定供应商快照的 supportsEmbedding 值。
     * 若找不到对应配置，记录错误日志并抛出异常。
     * @return 基于静态配置的 ProviderSnapshot
     * @throws IllegalArgumentException 当配置缺失时抛出
     */
    private ProviderSnapshot loadProviderFromPropertiesOrThrow(String providerId) {
        ProviderConfig config = properties.getProviders().get(providerId);
        if (config == null) {
            log.error("[LlmProviderRegistry] Provider config not found: {}", providerId);
            throw new IllegalArgumentException("Unknown LLM provider: " + providerId);
        }
        boolean supportsEmbedding = Boolean.TRUE.equals(config.getSupportsEmbedding())
                || !isBlank(config.getEmbeddingModel());
        return new ProviderSnapshot(
                providerId,
                config.getBaseUrl(),
                config.getApiKey(),
                config.getModel(),
                config.getEmbeddingModel(),
                config.getEmbeddingDimensions(),
                supportsEmbedding,
                config.getTemperature()
        );
    }

    /**
     * 判断字符串是否为 null 或仅含空白字符。
     *
     * @param value 待检查的字符串
     * @return true 表示空或空白，否则 false
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 解析向量的维度指标，若维度不为 null 且 > 0 则直接返回。否则返回配置类中的默认维度值。
     * @param configuredDimensions 单个 provider 配置的维度（可能为 null）
     */
    private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
        if (configuredDimensions != null && configuredDimensions > 0) {
            return configuredDimensions;
        }
        return properties.getEmbeddingDimensions();
    }

    /**
     * 通过模型名称判断该模型是否属于“聊天模型”范畴（启发式）。
     * <p>
     * 用于 Spring AI 2.0 中根据模型名自动选择聊天或嵌入 endpoint。
     * 匹配规则：模型名小写后以常见厂商前缀开头。
     *
     * @param model 模型名称（如 "glm-4", "deepseek-chat"）
     * @return true 表示更像聊天模型，false 可能是嵌入或其他模型
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
     * 不可变快照对象，封装单个 LLM 提供商的全部连接参数与元数据。
     * <p>
     * 用于运行时缓存，避免重复查询数据库或解密。
     *
     * @param id                   提供商标识
     * @param baseUrl              API 基础地址
     * @param apiKey               明文 API Key（已解密）
     * @param model                默认聊天模型名称
     * @param embeddingModel       嵌入模型名称（可能为空）
     * @param embeddingDimensions  嵌入向量维度（可能为空，需配合全局默认值）
     * @param supportsEmbedding    是否支持嵌入能力
     * @param temperature          默认温度参数（可能为空）
     */
    private record ProviderSnapshot(
            String id,
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

