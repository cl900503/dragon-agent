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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * RAG Embedding 模型配置。
 *
 * 通过 TEI (Text Embeddings Inference) 容器调用 BGE-M3 模型，
 * 使用标准 HTTP 客户端同步调用，避免 WebFlux 事件循环阻塞。
 *
 * @author 陈龙
 * @since 2026-06-02
 */
@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    /**
     * 创建 BGE-M3 Embedding 模型 Bean。
     *
     * 仅在没有其他 EmbeddingModel 实现时生效，保证可以替换为其他模型。
     *
     * @param teiBaseUrl TEI 服务地址，默认 http://localhost:8081/v1
     * @return TeiEmbeddingModel 实例
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    public EmbeddingModel embeddingModel(
            @Value("${app.embedding.tei.base-url:http://localhost:8081/v1}") String teiBaseUrl) {
        log.info("BGE-M3 Embedding via TEI at {}", teiBaseUrl);
        return new TeiEmbeddingModel(teiBaseUrl);
    }

    /**
     * 通过 TEI REST API 调用 BGE-M3 模型的 EmbeddingModel 实现。
     *
     * TEI 的 /v1/embeddings 端点兼容 OpenAI Embedding API 格式。
     * 使用 JDK HttpClient 同步调用，适配 EmbeddingModel 的同步接口。
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
