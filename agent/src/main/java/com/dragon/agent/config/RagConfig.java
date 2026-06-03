package com.dragon.agent.config;

import org.springframework.context.annotation.Configuration;

/**
 * RAG 配置。
 *
 * EmbeddingModel 由 spring-ai-starter-model-openai 自动配置（spring.ai.openai.*），
 * VectorStore 由 spring-ai-starter-vector-store-milvus 自动配置（spring.ai.vectorstore.milvus.*）。
 *
 * @author 陈龙
 * @since 2026-06-02
 */
@Configuration
public class RagConfig {
}
