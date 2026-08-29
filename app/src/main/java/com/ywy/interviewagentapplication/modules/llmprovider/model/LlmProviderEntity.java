package com.ywy.interviewagentapplication.modules.llmprovider.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 所有 LLM Provider 的配置
 * 每个 LLM Provider 都对应一个 LLM Model 和 配套的 Embedding Model（如果存在）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "llm_provider_config")
public class LlmProviderEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    /**
     * 存储 AES-GCM 的密文。<p>
     * api_key = api_key_ciphertext + api_key_nonce
     */
    @Column(name = "api_key_ciphertext", nullable = false, length = 4096)
    private String apiKeyCiphertext;

    /**
     * 存储随机数。<p>
     * api_key = api_key_ciphertext + api_key_nonce
     */
    @Column(name = "api_key_nonce", nullable = false, length = 64)
    private String apiKeyNonce;

    @Column(nullable = false, length = 128)
    private String model;

    @Column(name = "embedding_model", length = 128)
    private String embeddingModel;

    @Column(name = "embedding_dimensions")
    private Integer embeddingDimensions;

    /**
     * 表示当前 provider 是否支持 向量嵌入功能
     */
    @Column(name = "supports_embedding", nullable = false)
    private boolean supportsEmbedding;

    private Double temperature;

    /**
     * 当前 Provider 是否启用
     */
    @Column(nullable = false)
    private boolean enabled;

    /**
     * 区分内置 Provider（从 application.yml 导入的）和用户手动创建的 Provider
     */
    @Column(nullable = false)
    private boolean builtin;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

