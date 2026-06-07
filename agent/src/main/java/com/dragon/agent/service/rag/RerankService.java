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
 * Cross-Encoder 重排序服务 —— BGE-Reranker 语义重排 + MMR 多样性去重。
 *
 * <p>双重排序管线：
 * <ol>
 *   <li>Cross-Encoder 语义重排：用 BGE-Reranker-v2-m3 对候选文档按与查询的相关性重新打分</li>
 *   <li>MMR 多样性重排：在保证相关性的前提下，剔除内容高度重复的文档片段</li>
 * </ol>
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

    /** MMR 多样性参数（0=最大多样性, 1=最大相关性），推荐 0.7 */
    @Value("${app.rerank.mmr-lambda:0.7}")
    private double mmrLambda;

    /** 启用 MMR 多样性去重 */
    @Value("${app.rerank.mmr-enabled:true}")
    private boolean mmrEnabled;

    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 对候选文档重排序——Cross-Encoder 语义重排 + MMR 多样性去重。
     *
     * @param query 用户查询
     * @param docs  候选文档（粗筛结果）
     * @return 重排序 + 去重后的文档，附带相关性分数
     */
    public RerankResult rerank(String query, List<Document> docs) {
        if (docs.isEmpty()) return new RerankResult(docs, List.of());

        // 阶段 1：Cross-Encoder 语义重排
        List<Document> reranked;
        List<RerankScore> scores;
        var ceResult = crossEncoderRerank(query, docs);
        reranked = ceResult.documents();
        scores = ceResult.scores();
        if (reranked.isEmpty()) return new RerankResult(reranked, scores);

        // 阶段 2：MMR 多样性去重
        if (mmrEnabled && reranked.size() > 1) {
            var mmrResult = maximalMarginalRelevance(query, reranked, scores, mmrLambda, rerankTopK);
            reranked = mmrResult.documents();
            scores = mmrResult.scores();
        }

        log.debug("Reranked {} docs → top {} (Cross-Encoder → MMR)", docs.size(), reranked.size());
        return new RerankResult(reranked, scores);
    }

    /**
     * Cross-Encoder 语义重排——调用 BGE-Reranker 服务。
     */
    RerankResult crossEncoderRerank(String query, List<Document> docs) {
        List<String> texts = docs.stream().map(Document::getText).collect(Collectors.toList());
        List<RerankScore> scores = new ArrayList<>();

        try {
            Map<String, Object> body = Map.of(
                    "query", query,
                    "texts", texts,
                    "truncate", true);

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
                scores.sort((a, b) -> Double.compare(b.score, a.score));
                scores = scores.subList(0, Math.min(rerankTopK * 2, scores.size())); // 多取一些给 MMR 用
                List<Document> reranked = scores.stream()
                        .map(s -> docs.get(s.index))
                        .collect(Collectors.toList());
                return new RerankResult(reranked, scores);
            }
        } catch (Exception e) {
            log.warn("Reranker unavailable: {}, falling back to original order", e.getMessage());
        }

        // 降级：Reranker 不可用时用原始顺序
        return fallbackRerank(docs);
    }

    /**
     * Maximal Marginal Relevance 算法——在保证相关性的前提下最大化结果多样性。
     *
     * <p>公式：MMR = argmax [ λ·relevance(d) - (1-λ)·max_similarity(d, selected) ]
     *
     * <p>相似度使用 Jaccard 字符 3-gram 计算（快速、语言无关）。
     *
     * @param query    用户查询（保留用于将来扩展）
     * @param docs     按相关性排序的文档列表
     * @param scores   对应的相关性分数
     * @param lambda   相关性/多样性权衡（0.7 = 偏相关性，0.5 = 均衡）
     * @param topK     最终返回数量
     */
    RerankResult maximalMarginalRelevance(String query, List<Document> docs, List<RerankScore> scores,
            double lambda, int topK) {
        if (docs.size() <= topK) {
            // 裁剪到 topK
            return new RerankResult(
                    docs.subList(0, Math.min(topK, docs.size())),
                    scores.subList(0, Math.min(topK, scores.size())));
        }

        // 预计算所有文档的 3-gram 集合
        List<Set<String>> docGrams = docs.stream()
                .map(d -> trigramSet(d.getText()))
                .collect(Collectors.toList());

        // 归一化相关性分数到 [0, 1]
        double maxScore = scores.stream().mapToDouble(s -> s.score).max().orElse(1.0);
        double minScore = scores.stream().mapToDouble(s -> s.score).min().orElse(0.0);
        final double scoreRange = Math.max(maxScore - minScore, 1e-6);

        List<Double> normalizedScores = scores.stream()
                .map(s -> (s.score - minScore) / scoreRange)
                .collect(Collectors.toList());

        // MMR 贪心选择
        List<Integer> selected = new ArrayList<>();
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) remaining.add(i);

        // 第一个：选最高分的
        selected.add(remaining.remove(0));

        while (selected.size() < topK && !remaining.isEmpty()) {
            int bestIdx = -1;
            double bestMmr = Double.NEGATIVE_INFINITY;

            for (int cand : remaining) {
                double relevance = normalizedScores.get(cand);
                double maxSim = 0;
                for (int sel : selected) {
                    double sim = jaccardSimilarity(docGrams.get(cand), docGrams.get(sel));
                    maxSim = Math.max(maxSim, sim);
                }
                double mmr = lambda * relevance - (1.0 - lambda) * maxSim;
                if (mmr > bestMmr) {
                    bestMmr = mmr;
                    bestIdx = cand;
                }
            }

            if (bestIdx >= 0) {
                selected.add(bestIdx);
                remaining.remove(Integer.valueOf(bestIdx));
            } else {
                break;
            }
        }

        List<Document> mmrDocs = selected.stream().map(docs::get).collect(Collectors.toList());
        List<RerankScore> mmrScores = selected.stream().map(scores::get).collect(Collectors.toList());

        log.debug("MMR: {} → {} docs (λ={})", docs.size(), mmrDocs.size(), lambda);
        return new RerankResult(mmrDocs, mmrScores);
    }

    /** Reranker 不可用时的降级策略：用原始排序结果 */
    private RerankResult fallbackRerank(List<Document> docs) {
        List<Document> fallback = docs.subList(0, Math.min(rerankTopK, docs.size()));
        List<RerankScore> scores = new ArrayList<>();
        for (int i = 0; i < fallback.size(); i++) {
            scores.add(new RerankScore(i, 1.0 - i * 0.05));
        }
        return new RerankResult(fallback, scores);
    }

    /**
     * 计算两个 3-gram 集合的 Jaccard 相似度。
     */
    static double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);

        Set<String> union = new HashSet<>(a);
        union.addAll(b);

        return (double) intersection.size() / union.size();
    }

    /**
     * 提取文本的字符 3-gram 集合。
     */
    static Set<String> trigramSet(String text) {
        Set<String> grams = new HashSet<>();
        if (text == null || text.length() < 3) {
            if (text != null) grams.add(text);
            return grams;
        }
        for (int i = 0; i <= text.length() - 3; i++) {
            grams.add(text.substring(i, i + 3));
        }
        return grams;
    }

    public record RerankResult(List<Document> documents, List<RerankScore> scores) {
    }

    public record RerankScore(int index, double score) {
    }
}
