package com.dragon.agent.service.rag;

import java.util.*;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Cross-Encoder 重排序服务 —— 调用 BGE-Reranker-v2-m3 对检索结果二次排序。
 *
 * @author 陈龙
 * @since 2026-06-05
 */
@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    @Value("${app.rerank.base-url:http://localhost:8082}")
    private String rerankUrl;

    @Value("${app.rerank.top-k:5}")
    private int rerankTopK;

    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 对候选文档重排序。
     *
     * @param query    用户查询
     * @param docs     候选文档（粗筛 top-20）
     * @return 重排序后的 top-5 文档 + 重排分数
     */
    public RerankResult rerank(String query, List<Document> docs) {
        if (docs.isEmpty()) return new RerankResult(docs, List.of());

        List<String> texts = docs.stream().map(Document::getText).collect(Collectors.toList());
        List<RerankScore> scores = new ArrayList<>();

        try {
            Map<String, Object> body = Map.of(
                    "query", query,
                    "texts", texts,
                    "truncate", true
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            ResponseEntity<String> resp = rest.postForEntity(
                    rerankUrl + "/rerank", request, String.class);

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                JsonNode root = mapper.readTree(resp.getBody());
                for (JsonNode item : root) {
                    int idx = item.get("index").asInt();
                    double score = item.get("score").asDouble();
                    scores.add(new RerankScore(idx, score));
                }
                // 按重排分数降序排列
                scores.sort((a, b) -> Double.compare(b.score, a.score));
                // 取 top-K
                scores = scores.subList(0, Math.min(rerankTopK, scores.size()));
                List<Document> reranked = scores.stream()
                        .map(s -> docs.get(s.index))
                        .collect(Collectors.toList());
                log.debug("Reranked {} docs → top {}", docs.size(), reranked.size());
                return new RerankResult(reranked, scores);
            }
        } catch (Exception e) {
            log.warn("Reranker unavailable: {}, falling back to original order", e.getMessage());
        }

        // 降级：Reranker 不可用时用原始顺序
        List<Document> fallback = docs.subList(0, Math.min(rerankTopK, docs.size()));
        for (int i = 0; i < fallback.size(); i++) {
            scores.add(new RerankScore(i, 1.0 - i * 0.05));
        }
        return new RerankResult(fallback, scores);
    }

    public record RerankResult(List<Document> documents, List<RerankScore> scores) {}
    public record RerankScore(int index, double score) {}
}
