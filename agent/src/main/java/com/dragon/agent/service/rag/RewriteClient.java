package com.dragon.agent.service.rag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 查询改写专用 LLM 客户端——调用轻量模型（deepseek-chat），独立于对话使用的推理模型。
 *
 * <p>通过 RestTemplate 直接调用 DeepSeek API，可指定不同于对话的模型和参数。</p>
 *
 * @author 陈龙
 * @since 2026-06-07
 */
@Service
public class RewriteClient {

    private static final Logger log = LoggerFactory.getLogger(RewriteClient.class);

    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${AI_API_KEY}")
    private String apiKey;

    @Value("${AI_BASE_URL:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${app.rag.rewrite-model:deepseek-chat}")
    private String model;

    /**
     * 调用轻量模型改写查询。
     *
     * @param query 用户原始查询
     * @return LLM 输出文本，失败返回 null
     */
    public String rewrite(String query) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", 60);
            body.put("temperature", 0.1);
            body.put("thinking", Map.of("type", "disabled"));
            body.put("messages", List.of(
                    Map.of("role", "user", "content",
                            "改写用户查询为更适合检索的表述，每行一个变体，最多3行：" + query)));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            String resp = rest.postForObject(baseUrl + "/v1/chat/completions", request, String.class);

            if (resp == null) return null;
            JsonNode root = mapper.readTree(resp);
            JsonNode choices = root.path("choices");
            if (choices.isEmpty()) return null;
            return choices.get(0).path("message").path("content").asText();
        } catch (Exception e) {
            log.warn("Rewrite API call failed: {}", e.getMessage());
            return null;
        }
    }
}
