package com.ywy.interviewagentapplication.modules.llmprovider.service;

import com.ywy.interviewagentapplication.common.config.LlmProviderProperties;
import com.ywy.interviewagentapplication.common.config.LlmProviderProperties.ProviderConfig;
import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.modules.llmprovider.model.LlmGlobalSettingEntity;
import com.ywy.interviewagentapplication.modules.llmprovider.model.LlmProviderEntity;
import com.ywy.interviewagentapplication.modules.llmprovider.repository.LlmGlobalSettingRepository;
import com.ywy.interviewagentapplication.modules.llmprovider.repository.LlmProviderRepository;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LLM Provider 启动引导服务，负责在应用启动时自动初始化 LLM Provider 数据和 LLM 全局设置。
 * <p>
 * <b>设计原因</b>：
 * 应用启动时，需要将 application.yml 中配置的 Provider 自动导入数据库（首次启动）或跳过（数据库已有 Provider）。这是"数据库优先，配置兜底"策略的关键实现。
 * <p>
 * <b>设计目标</b>：
 * <ul>
 *   <li>将配置文件中的 Provider 定义（${app.ai.providers}）自动同步到数据库，实现“配置即数据”</li>
 *   <li>确保全局设置（默认聊天/Embedding Provider）始终存在，避免首次使用时空指针</li>
 *   <li>支持 Provider 的加密存储（通过 {@link ApiKeyEncryptionService}）</li>
 * </ul>
 * <p>
 * <b>执行时机</b>：Spring 容器启动时注入该类的 Bean 后，通过被 @PostConstruct 标注的 {@link #seedProvidersIfNecessary()} 触发。
 * <p>
 * <b>协作关系</b>：
 * <ul>
 *   <li>依赖 {@link LlmProviderProperties} 读取配置</li>
 *   <li>依赖 {@link LlmProviderRepository} 和 {@link LlmGlobalSettingRepository} 进行数据库操作</li>
 *   <li>依赖 {@link ApiKeyEncryptionService} 加密 API Key</li>
 * </ul>
 * <p>
 * <b>注意</b>：此服务仅在数据库无 Provider 记录时执行 seeding，不会覆盖已有数据，
 * 保证生产环境手动修改的配置不被自动覆盖。
 *
 * @see LlmProviderProperties
 * @see ApiKeyEncryptionService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderBootstrapService {

    private final LlmProviderProperties properties;
    private final LlmProviderRepository providerRepository;
    private final LlmGlobalSettingRepository globalSettingRepository;
    private final ApiKeyEncryptionService encryptionService;

    /**
     * 启动初始化入口：检查并填充 LLM Provider 数据及 LLM 全局设置。
     * <p>
     * <b>执行流程</b>：
     * <ol>
     *   <li>若数据库中没有 Provider 记录，则从配置文件 {@code app.ai.providers} 中读取并保存</li>
     *   <li>确保全局设置记录存在，若不存在则使用配置类信息创建</li>
     * </ol>
     * <p>
     * <b>事务性</b>：整个操作标记为 {@link Transactional}，确保原子性。
     * <p>
     * <b>异常</b>：若加密密钥未配置或加密失败，会抛出 {@link BusinessException}，导致应用启动失败（符合预期）。
     */
    @PostConstruct
    @Transactional
    public void seedProvidersIfNecessary() {
        // 如果 LLM Provider 表中的数据为 0 条记录，则插入数据
        if (providerRepository.count() == 0) {
            seedProviders();
        }
        ensureGlobalSetting();
    }

    /**
     * 从配置类读取 Provider 数据并插入到数据库。
     * <p>
     * <b>处理逻辑</b>：
     * <ul>
     *   <li>遍历 {@code properties.getProviders()} 中的每个 Provider 配置</li>
     *   <li>校验必要字段（id、baseUrl、model）非空，不满足则跳过并记录警告</li>
     *   <li>使用 {@link ApiKeyEncryptionService#encrypt(String)} 加密 API Key</li>
     *   <li>根据配置类信息判断每个 LLM Provider 是否支持 Embedding（若配置了 embeddingModel 或 supportsEmbedding=true）</li>
     *   <li>构建 {@link LlmProviderEntity} 并插入数据库</li>
     * </ul>
     * <p>
     * <b>注意</b>：若配置类中无任何 Provider，仅记录警告而不抛异常（允许后续手动添加）。
     * <p>
     * <b>协作</b>：由 {@link #seedProvidersIfNecessary()} 调用。
     */
    private void seedProviders() {
        Map<String, ProviderConfig> providers = properties.getProviders();
        if (providers == null || providers.isEmpty()) {
            log.warn("No app.ai.providers seed configuration found");
            return;
        }

        providers.forEach((id, config) -> {
            if (isBlank(id) || config == null || isBlank(config.getBaseUrl()) || isBlank(config.getModel())) {
                log.warn("Skip invalid provider seed: id={}", id);
                return;
            }
            ApiKeyEncryptionService.EncryptedValue encrypted =
                    encryptionService.encrypt(config.getApiKey() != null ? config.getApiKey() : "");
            boolean supportsEmbedding = Boolean.TRUE.equals(config.getSupportsEmbedding())
                    || !isBlank(config.getEmbeddingModel());

            LlmProviderEntity entity = LlmProviderEntity.builder()
                    .id(id)
                    .baseUrl(config.getBaseUrl())
                    .apiKeyNonce(encrypted.nonce())
                    .apiKeyCiphertext(encrypted.ciphertext())
                    .model(config.getModel())
                    .embeddingModel(trimOrNull(config.getEmbeddingModel()))
                    .embeddingDimensions(resolveEmbeddingDimensions(config.getEmbeddingDimensions()))
                    .supportsEmbedding(supportsEmbedding)
                    .temperature(config.getTemperature())
                    .enabled(true)
                    .builtin(true)
                    .build();
            providerRepository.save(entity);
        });
        log.info("Seeded {} LLM providers from application configuration", providerRepository.count());
    }

    /**
     * 确保全局设置记录存在。若存在则直接返回，否则使用配置类信息创建。
     * <p>
     * <b>选择策略</b>：
     * <ul>
     *   <li><b>默认聊天 Provider</b>：优先使用 {@code properties.getDefaultProvider()}，
     *   若该值不存在，则使用数据库 Provider 表中的第一个 Provider 的 ID，若表为空则回退为 "dashscope"</li>
     *   <li><b>默认 Embedding Provider</b>：优先使用 {@code properties.getDefaultEmbeddingProvider()}，
     *   若不存在或未启用或不支持 Embedding，则从数据库中查找第一个满足这些条件的 Provider 并返回其 ID，
     *   若仍找不到，则回退到全局设置中 LLM Model 对应的 Provider（即使它可能不支持 Embedding，但至少不会报错）</li>
     * </ul>
     * <p>
     * <b>协作</b>：由 {@link #seedProvidersIfNecessary()} 在 Provider 初始化后调用。
     */
    private void ensureGlobalSetting() {
        if (globalSettingRepository.existsById(LlmGlobalSettingEntity.SINGLETON_ID)) {
            return;
        }
        // 默认 LLM 模型
        String defaultChatProvider = resolveExistingProvider(
                properties.getDefaultProvider(),
                providerRepository.findAll().stream().findFirst().map(LlmProviderEntity::getId).orElse("dashscope")
        );
        String configuredEmbeddingProvider = !isBlank(properties.getDefaultEmbeddingProvider())
                ? properties.getDefaultEmbeddingProvider()  // 默认嵌入模型
                : defaultChatProvider;
        String defaultEmbeddingProvider = resolveExistingEmbeddingProvider(configuredEmbeddingProvider, defaultChatProvider);

        globalSettingRepository.save(LlmGlobalSettingEntity.builder()
                .id(LlmGlobalSettingEntity.SINGLETON_ID)
                .defaultChatProviderId(defaultChatProvider)
                .defaultEmbeddingProviderId(defaultEmbeddingProvider)
                .build());
        log.info("Initialized LLM global setting: chatProvider={}, embeddingProvider={}",
                defaultChatProvider, defaultEmbeddingProvider);
    }

    /**
     * 在数据库查找指定的 Provider ID 是否存在。存在则直接返回，否则使用回退策略，返回备用的 Provider ID
     *
     * @param preferredProvider 指定的 Provider ID（可能为空或不存在于数据库）
     * @param fallbackProvider  回退 Provider ID（需自行保证该 ID 有效）
     * @return 最终选定的 Provider ID
     */
    private String resolveExistingProvider(String preferredProvider, String fallbackProvider) {
        if (!isBlank(preferredProvider) && providerRepository.existsById(preferredProvider)) {
            return preferredProvider;
        }
        return fallbackProvider;
    }

    /**
     * 从数据库中查找指定的 Provider ID 是否存在且允许使用且支持 Embedding，若条件同时满足则直接返回该 Provider ID。
     * <p>
     * 否则从数据库中查找第一个同时满足 enabled=true, supportsEmbedding=true 且 embeddingModel 非空的 Provider，找到则直接返回其 ID。
     * <p>
     * 否则使用回退策略，返回备用的 Provider ID（需自行保证该 ID 有效）。
     * @param preferredProvider 配置中指定的默认 Embedding Provider ID（可能为空或无效）
     * @param fallbackProvider  回退 Provider ID（通常是默认聊天 Provider）
     * @return 最终选定的有效支持 Embedding 的 Provider ID
     */
    private String resolveExistingEmbeddingProvider(String preferredProvider, String fallbackProvider) {
        return providerRepository.findById(preferredProvider)
                .filter(this::canProvideEmbedding)
                .map(LlmProviderEntity::getId)
                .orElseGet(() -> providerRepository.findAll().stream()
                        .filter(this::canProvideEmbedding)
                        .findFirst()
                        .map(LlmProviderEntity::getId)
                        .orElse(fallbackProvider));
    }

    /**
     * 判断指定的 Provider 是否有效支持 Embedding
     */
    private boolean canProvideEmbedding(LlmProviderEntity provider) {
        return provider.isEnabled()
                && provider.isSupportsEmbedding()
                && !isBlank(provider.getEmbeddingModel());
    }

    /**
     * 解析向量的维度指标，若维度不为 null 且 > 0 则直接返回。否则返回配置类中的默认维度值。
     */
    private Integer resolveEmbeddingDimensions(Integer configuredDimensions) {
        if (configuredDimensions != null && configuredDimensions > 0) {
            return configuredDimensions;
        }
        return properties.getEmbeddingDimensions();
    }

    /**
     * 若字符串为 null 或空串，则直接返回 null。否则返回消除首尾空格的字符串。
     */
    private String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

