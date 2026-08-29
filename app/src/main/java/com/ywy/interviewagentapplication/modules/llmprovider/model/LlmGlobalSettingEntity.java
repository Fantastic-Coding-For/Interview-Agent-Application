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
 * LLM 全局设置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "llm_global_setting")
public class LlmGlobalSettingEntity {

    /**
     * 单例设计，为“单行配置表”定义一个固定的、唯一的主键 ID 常量。
     */
    public static final Long SINGLETON_ID = 1L;

    @Id
    private Long id;

    /**
     * 默认 LLM Model 对应的 Provider
     */
    @Column(name = "default_chat_provider_id", nullable = false, length = 64)
    private String defaultChatProviderId;

    /**
     * 默认 Embedding Model 对应的 Provider
     */
    @Column(name = "default_embedding_provider_id", nullable = false, length = 64)
    private String defaultEmbeddingProviderId;

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

