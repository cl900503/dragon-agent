package com.dragon.agent.service.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.dragon.agent.service.KnowledgeBaseService;
import com.dragon.agent.repository.RagSearchLogRepository;

/**
 * RAG 语义检索服务——负责 Embedding、Hybrid Search、Rerank 重排的完整检索管线。
 *
 * <p>从 DocumentService 拆分出来，遵循单一职责原则：DocumentService 只负责文档生命周期，
 * 检索逻辑由此服务独立承担。</p>
 *
 * @author 陈龙
 * @since 2026-06-06
 */
@Service
public class RagSearchService {

    private static final Logger log = LoggerFactory.getLogger(RagSearchService.class);

    @Autowired
    private BgeM3Client bgeM3;

    @Autowired
    private HybridSearchService hybridSearch;

    @Autowired
    private RerankService rerankService;

    @Autowired(required = false)
    private VectorStore vectorStore;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private RagSearchLogRepository searchLogRepository;

    @Value("${app.rag.search-limit:20}")
    private int searchLimit;

    /**
     * RAG 语义检索——Embedding + Hybrid Search + Rerank 完整管线。
     *
     * <p>用户可检索：自己的所有文档 + 有权限的知识库中的文档。</p>
     *
     * @param query  用户查询文本
     * @param userId 当前用户 ID
     * @return 检索结果（LLM 上下文 + 追溯信息）
     */
    public RagResult retrieveContext(String query, Long userId) {
        if (vectorStore == null || userId == null) {
            return RagResult.EMPTY;
        }

        try {
            List<String> accessibleKbIds = knowledgeBaseService.getAccessibleKbIds(userId);
            StringBuilder filter = new StringBuilder("userId == '" + userId + "'");
            if (!accessibleKbIds.isEmpty()) {
                filter.append(" || kbId in [");
                filter.append(accessibleKbIds.stream()
                        .map(id -> "'" + id + "'")
                        .collect(Collectors.joining(", ")));
                filter.append("]");
            }

            long start = System.currentTimeMillis();
            var emb = bgeM3.embed(query);
            if (emb == null || !emb.containsKey("dense")) {
                return RagResult.EMPTY;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> raw = hybridSearch.hybridSearch(
                    (List<Double>) emb.get("dense"), filter.toString(), searchLimit);
            long retrievalMs = System.currentTimeMillis() - start;

            if (raw.isEmpty()) {
                return RagResult.EMPTY;
            }

            List<Document> candidates = raw.stream().map(r -> {
                Document d = new Document(
                        (String) r.getOrDefault("content", ""), new LinkedHashMap<>(r));
                Object s = r.get("score");
                if (s instanceof Number) {
                    d.getMetadata().put("score", ((Number) s).doubleValue());
                }
                return d;
            }).collect(Collectors.toList());

            var rerankResult = rerankService.rerank(query, candidates);
            List<Document> results = rerankResult.documents();
            for (int i = 0; i < Math.min(results.size(), rerankResult.scores().size()); i++) {
                results.get(i).getMetadata().put("score", rerankResult.scores().get(i).score());
            }

            double topScore = results.stream()
                    .mapToDouble(d -> d.getScore() != null ? d.getScore() : 0).max().orElse(0);
            double avgScore = results.stream()
                    .mapToDouble(d -> d.getScore() != null ? d.getScore() : 0).average().orElse(0);
            try {
                searchLogRepository.save(new com.dragon.agent.entity.RagSearchLog(
                        userId, query, String.join(",", accessibleKbIds),
                        results.size(), topScore, avgScore, retrievalMs, true));
            } catch (Exception ignored) {
                // 日志写入失败不影响检索主流程
            }

            log.debug("RAG: {} candidates -> {} reranked ({}ms)", candidates.size(), results.size(), retrievalMs);
            List<Map<String, Object>> traces = buildTraces(results);
            return new RagResult(formatContext(results), traces);
        } catch (Exception e) {
            log.warn("RAG 检索失败: {}", e.getMessage());
            return RagResult.EMPTY;
        }
    }

    /**
     * 构建检索追溯信息列表，返回给前端展示引用来源。
     */
    public List<Map<String, Object>> buildTraces(List<Document> documents) {
        List<Map<String, Object>> traces = new ArrayList<>();
        for (Document doc : documents) {
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("documentName", doc.getMetadata().getOrDefault("originalName", "未知文档"));
            trace.put("chunkIndex", Integer.parseInt(
                    (String) doc.getMetadata().getOrDefault("chunkIndex", "0")));
            Object score = doc.getMetadata().get("score");
            trace.put("score", score instanceof Number
                    ? ((Number) score).doubleValue()
                    : doc.getScore() != null ? doc.getScore() : 0.0);
            String text = doc.getText();
            trace.put("contentSnippet",
                    text != null && text.length() > 200
                            ? text.substring(0, 200) + "..."
                            : text);
            traces.add(trace);
        }
        return traces;
    }

    /**
     * 将检索结果格式化为 LLM 上下文文本。
     */
    public String formatContext(List<Document> documents) {
        StringBuilder sb = new StringBuilder();
        for (Document doc : documents) {
            String name = (String) doc.getMetadata().getOrDefault("originalName", "未知");
            sb.append("[%s]\n%s\n\n".formatted(name, doc.getText()));
        }
        return sb.toString();
    }

    /**
     * RAG 检索结果封装——包含 LLM 上下文和前端展示用的追溯信息。
     */
    public record RagResult(String context, List<Map<String, Object>> traces) {
        public static final RagResult EMPTY = new RagResult("", List.of());

        public boolean isEmpty() {
            return context == null || context.isEmpty();
        }
    }
}
