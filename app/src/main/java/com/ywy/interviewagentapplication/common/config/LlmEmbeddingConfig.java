package com.ywy.interviewagentapplication.common.config;

import com.ywy.interviewagentapplication.common.ai.LlmProviderRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedding Bean 注册：通过委托模式实现运行时 Provider 切换
 * <p>设计原因：
 *   1. Spring AI 的 PgVectorStore 需要注入 EmbeddingModel Bean
 *   2. 但生产环境中存在多个 EmbeddingModel
 *   3. 解决方案：注册一个"代理"Bean，其底层委托给 LlmProviderRegistry，每次调用会获取默认的 Embedding Model（可切换默认的 Embedding Model）。
 * <p>设计模式：委托模式（Delegation Pattern）
 * <p>被依赖方：PgVectorStore（Spring AI 自动配置）
 */
@Configuration
@Slf4j
public class LlmEmbeddingConfig {
    /**
     * 注册 EmbeddingModel Bean，委托给 LlmProviderRegistry
     * 每次调用时动态查找当前默认使用的 EmbeddingModel
     */
    @Bean
    public EmbeddingModel embeddingModel(LlmProviderRegistry registry) {
        log.info("EmbeddingModel bean initialized as registry delegate");
        // 匿名内部类实现 EmbeddingModel 接口，所有调用转发给 Registry
        return new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                // 批量向量化：委托给默认的 EmbeddingModel
                return registry.getDefaultEmbeddingModel().call(request);
            }

            @Override
            public float[] embed(Document document) {
                // 单条向量化：委托给默认的 EmbeddingModel
                return registry.getDefaultEmbeddingModel().embed(document);
            }
        };
    }
}
