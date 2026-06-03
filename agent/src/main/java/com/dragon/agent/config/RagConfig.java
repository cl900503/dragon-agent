package com.dragon.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 配置类。
 *
 * EmbeddingModel 由 spring-ai-starter-model-openai 自动配置，
 * 通过 spring.ai.openai.* 指向 TEI BGE-M3（OpenAI 兼容 API）。
 * VectorStore 由 spring-ai-starter-vector-store-milvus 自动配置。
 *
 * @author 陈龙
 * @since 2026-06-02
 */
@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    static {
        log.info("RAG initialized — EmbeddingModel and VectorStore auto-configured by Spring AI");
    }
}
