package com.ywy.interviewagentapplication.modules.llmprovider.service;

import com.ywy.interviewagentapplication.common.config.LlmProviderProperties;
import com.ywy.interviewagentapplication.common.exception.BusinessException;
import com.ywy.interviewagentapplication.common.exception.ErrorCode;
import jakarta.annotation.PostConstruct;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Provider API Key 加密/解密服务，采用 AES-GCM 认证加密。
 * <p>
 * <b>设计背景</b>：Provider API Key（如 OpenAI、Azure 的密钥）属于敏感信息，需要在数据库中加密存储，
 * 使用时解密。本服务提供统一的加密/解密能力，并支持灵活的密钥配置策略。
 * <p>
 * <b>安全特性</b>：
 * <ul>
 *   <li><b>算法</b>：AES-GCM（Galois/Counter Mode），提供认证加密（AEAD），防止篡改</li>
 *   <li><b>随机数</b>：每次加密生成 12 字节随机 Nonce（安全随机数），保证相同明文每次加密时的密文不同</li>
 *   <li><b>AES 密钥长度</b>：256 位（32 字节），符合 AES-256 强度</li>
 *   <li><b>Tag 长度</b>：128 位，提供强认证</li>
 * </ul>
 * <p>
 * <b>密钥配置策略</b>（三级回退）：
 * <ol>
 *   <li><b>首选</b>：从配置属性 {@link LlmProviderProperties.SecurityConfig#getApiKeyEncryptionKey()} 读取 Base64 编码的 32 字节密钥</li>
 *   <li><b>派生</b>：若配置的不是有效的 Base64，则通过 SHA-256 对字符串进行摘要，生成 32 字节密钥（兼容人类可读密码）</li>
 *   <li><b>开发 Fallback</b>：若未配置密钥，且 {@code requireEncryptionKey=false && allowFallbackEncryptionKey=true}，
 *   则使用硬编码的 AES 密钥：{@link #DEV_FALLBACK_KEY}（仅用于本地开发，生产环境必须明确配置）</li>
 * </ol>
 * <p>
 * <b>异常处理</b>：
 * <ul>
 *   <li>密钥未配置且不允许 fallback 时，抛出 {@link BusinessException}（ErrorCode.PROVIDER_CONFIG_READ_FAILED）</li>
 *   <li>加密/解密失败时抛出 BusinessException（分别对应 WRITE_FAILED / READ_FAILED）</li>
 * </ul>
 *
 * @see LlmProviderProperties
 * @see <a href="https://csrc.nist.gov/publications/detail/sp/800-38d/final">NIST SP 800-38D (GCM)</a>
 */
@Slf4j
@Service
public class ApiKeyEncryptionService {
    // 12 字节 Nonce（安全随机数）
    private static final int NONCE_BYTES = 12;
    // 128 位认证标签
    private static final int GCM_TAG_BITS = 128;
    // 标准 AES-GCM 算法
    private static final String CIPHER = "AES/GCM/NoPadding";
    // 仅限本地开发时使用的硬编码简单 AES 密钥
    private static final String DEV_FALLBACK_KEY =
            "interview-guide-dev-only-provider-api-key-encryption";

    private final LlmProviderProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();
    private SecretKeySpec secretKey;

    public ApiKeyEncryptionService(LlmProviderProperties properties) {
        this.properties = properties;
    }

    /**
     * 初始化加密密钥：在 Bean 构建后自动调用。
     * <p>
     * 首先解析配置中的密钥字符串，通过 {@link #resolveConfiguredKey(LlmProviderProperties.SecurityConfig)} 获取最终密钥，
     * 再通过 {@link #resolveKeyBytes(String)} 转换为 32 字节的 AES 密钥。
     *
     * @throws BusinessException 若密钥解析失败或配置不满足要求
     */
    @PostConstruct
    void init() {
        String configuredKey = resolveConfiguredKey(properties.getSecurity());
        secretKey = new SecretKeySpec(resolveKeyBytes(configuredKey), "AES");
    }

    /**
     * 从配置中获取满足条件的密钥字符串（支持三级回退策略）。
     * <p>
     * <b>决策树</b>：
     * <pre>
     * 1. 若配置了 apiKeyEncryptionKey 且非空 → 直接返回
     * 2. 否则若 requireEncryptionKey == true → 抛出异常（强制要求配置）
     * 3. 否则若 allowFallbackEncryptionKey == true → 使用开发环境备用的硬编码密钥，并记录警告
     * 4. 否则 → 抛出异常（未配置且未显式允许 fallback）
     * </pre>
     * <p>
     * <b>协作</b>：由 {@link #init()} 调用，其结果传入 {@link #resolveKeyBytes(String)}。
     *
     * @param security 安全配置对象（可为 null）
     * @return 最终选定的 AES 密钥字符串
     * @throws BusinessException 当无法获取有效密钥时
     */
    private String resolveConfiguredKey(LlmProviderProperties.SecurityConfig security) {
        String configuredKey = security != null ? security.getApiKeyEncryptionKey() : null;
        if (configuredKey != null && !configuredKey.isBlank()) {
            return configuredKey;
        }

        boolean requireEncryptionKey = security == null || security.isRequireEncryptionKey();
        if (requireEncryptionKey) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                    "APP_AI_CONFIG_ENCRYPTION_KEY 未配置，无法初始化 Provider API Key 加密");
        }

        boolean allowFallback = security.isAllowFallbackEncryptionKey();
        if (!allowFallback) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                    "APP_AI_CONFIG_ENCRYPTION_KEY 未配置，且未显式允许 Provider API Key 开发 fallback");
        }

        log.warn("APP_AI_CONFIG_ENCRYPTION_KEY is not configured; using explicitly enabled fallback key");
        return DEV_FALLBACK_KEY;
    }

    /**
     * 将 AES 密钥字符串转换为二进制格式。
     * <p>
     * <b>转换策略</b>（支持两种格式）：
     * <ol>
     *   <li><b>Base64 格式</b>：若字符串为有效的 Base64 且解码后长度为 32 字节，直接使用（推荐）</li>
     *   <li><b>派生格式</b>：否则，对字符串进行 SHA-256 摘要，生成 32 字节（兼容人类可读密码，但安全性依赖于密码强度）</li>
     * </ol>
     * <p>
     * <b>协作</b>：由 {@link #init()} 调用，其结果作为 {@link SecretKeySpec} 的构造参数。
     *
     * @param configuredKey AES 密钥字符串（推荐通过 {@link #resolveConfiguredKey} 获取）
     * @return 32 字节的密钥字节数组
     */
    private byte[] resolveKeyBytes(String configuredKey) {
        String trimmed = configuredKey.trim();
        try {
            byte[] decoded = Base64.getDecoder().decode(trimmed);
            if (decoded.length == 32) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to SHA-256 derivation for human-readable keys.
        }
        return sha256(trimmed);
    }

    /**
     * 对字符串进行 SHA-256 摘要，生成 32 字节的哈希值。
     * <p>
     * <b>使用场景</b>：当配置的密钥不是有效的 32 字节 Base64 时，通过散列派生密钥。
     * <p>
     * <b>注意</b>：SHA-256 是单向散列，派生密钥强度取决于原密码的熵，
     * 生产环境建议直接配置 32 字节的 Base64 密钥。
     *
     * @param value 输入字符串
     * @return 32 字节的 SHA-256 摘要
     * @throws BusinessException 如果算法不可用（理论上不会发生）
     */
    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                    "初始化 Provider API Key 加密密钥失败", e);
        }
    }

    /**
     * 加密明文字符串，返回包含 Nonce 和密文的封装对象。
     * <p>
     * <b>加密流程</b>：
     * <ol>
     *   <li>生成 12 字节随机 Nonce（使用 {@link SecureRandom}）</li>
     *   <li>初始化 AES-GCM Cipher（加密模式，128 位 Tag）</li>
     *   <li>加密 UTF-8 编码的明文，得到密文字节</li>
     *   <li>分别对 Nonce 和密文进行 Base64 编码，便于存储</li>
     * </ol>
     * <p>
     * <b>安全性</b>：每次加密使用全新随机 Nonce，确保同一明文产生不同密文（语义安全）。
     * <p>
     * <b>异常</b>：加密失败时抛出 {@link BusinessException}（ErrorCode.PROVIDER_CONFIG_WRITE_FAILED）。
     *
     * @param plainText 明文（如 API Key）
     * @return 包含 Base64 编码的 Nonce 和密文的 {@link EncryptedValue} 对象
     * @throws BusinessException 加密失败
     */
    public EncryptedValue encrypt(String plainText) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            return new EncryptedValue(
                    Base64.getEncoder().encodeToString(nonce),
                    Base64.getEncoder().encodeToString(ciphertext)
            );
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_WRITE_FAILED,
                    "加密 Provider API Key 失败", e);
        }
    }

    /**
     * 解密由 {@link #encrypt(String)} 生成的密文。
     * <p>
     * <b>解密流程</b>：
     * <ol>
     *   <li>Base64 解码 Nonce 和密文</li>
     *   <li>初始化 AES-GCM Cipher（解密模式，使用相同 Nonce 和 Tag）</li>
     *   <li>解密得到明文字节，转为 UTF-8 字符串</li>
     * </ol>
     * <p>
     * <b>异常</b>：解密失败（密钥错误、篡改、格式错误）时抛出 {@link BusinessException}
     * （ErrorCode.PROVIDER_CONFIG_READ_FAILED），并提示检查加密密钥配置。
     *
     * @param nonceBase64      Base64 编码的 Nonce（由加密时生成）
     * @param ciphertextBase64 Base64 编码的密文
     * @return 解密后的明文字符串
     * @throws BusinessException 解密失败
     */
    public String decrypt(String nonceBase64, String ciphertextBase64) {
        try {
            byte[] nonce = Base64.getDecoder().decode(nonceBase64);
            byte[] ciphertext = Base64.getDecoder().decode(ciphertextBase64);

            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] plainText = cipher.doFinal(ciphertext);
            return new String(plainText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_READ_FAILED,
                    "解密 Provider API Key 失败，请检查 APP_AI_CONFIG_ENCRYPTION_KEY", e);
        }
    }

    /**
     * 加密结果的数据载体，包含 Base64 编码的 Nonce 和密文。
     * <p>
     * 两者必须成对存储和传递，解密时需要同时提供。
     * <p>
     * <b>存储建议</b>：将 nonce 和 ciphertext 分别存为数据库的两个字段，或合并为一个 JSON 对象。
     */
    public record EncryptedValue(String nonce, String ciphertext) {
    }
}

