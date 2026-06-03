package com.dragon.agent.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * RAG Embedding 配置——BGE-M3 via TEI (Text Embeddings Inference)。
 *
 * 通过 Spring AI 标准 EmbeddingModel 接口接入，实现与 MilvusVectorStore 的无缝集成。
 * TEI 提供 OpenAI 兼容的 /v1/embeddings 端点，使用 JDK HttpClient 同步调用。
 *
 * 注：Spring AI 2.0.0-M8 的 OpenAI 自动配置无法正确解析自定义 base-url 指向 TEI，
 * 因此通过 @Bean 显式创建 EmbeddingModel，后续升级 Spring AI 版本后可切换为自动配置。
 *
 * @author 陈龙
 * @since 2026-06-02
 */
@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    /**
     * 创建 BGE-M3 Embedding 实现，通过 TEI REST API 调用。
     *
     * @param teiBaseUrl TEI 服务地址
     * @return EmbeddingModel 实现
     */
    @Bean
    public EmbeddingModel embeddingModel(
            @Value("${app.embedding.tei.base-url:http://localhost:8081/v1}") String teiBaseUrl) {
        log.info("BGE-M3 Embedding via TEI at {}", teiBaseUrl);
        return new TeiEmbeddingModel(teiBaseUrl);
    }

    /**
     * TEI BGE-M3 Embedding 实现，遵循 Spring AI EmbeddingModel 接口规范。
     */
    static class TeiEmbeddingModel implements EmbeddingModel {

        private static final ObjectMapper objectMapper = new ObjectMapper();

        private final String baseUrl;
        private final HttpClient httpClient;

        TeiEmbeddingModel(String baseUrl) {
            this.baseUrl = baseUrl;
            this.httpClient = HttpClient.newHttpClient();
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<String> texts = request.getInstructions();
            List<Embedding> results = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                results.add(new Embedding(embed(texts.get(i)), i));
            }
            return new EmbeddingResponse(results);
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public float[] embed(String text) {
            try {
                String requestBody = objectMapper.writeValueAsString(
                        Map.of("input", text, "model", "bge-m3"));
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/embeddings"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());

                @SuppressWarnings("unchecked")
                Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
                if (data == null || data.isEmpty()) {
                    return new float[0];
                }

                @SuppressWarnings("unchecked")
                List<Number> values = (List<Number>) data.get(0).get("embedding");
                float[] vector = new float[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    vector[i] = values.get(i).floatValue();
                }
                return vector;

            } catch (Exception e) {
                log.warn("TEI embedding request failed: {}", e.getMessage());
                return new float[0];
            }
        }
    }
}
