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

    @Autowired(required = false)
    private QueryCacheService cacheService;

    @Value("${app.rag.search-limit:20}")
    private int searchLimit;

    @Value("${app.rag.similarity-threshold:0.2}")
    private double similarityThreshold;

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

        // 检索缓存——相同查询+用户直接返回缓存结果
        if (cacheService != null) {
            var cached = cacheService.getSearchResult(query, userId);
            if (cached != null) return cached;
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

            // Embedding 缓存——相同文本避免重复调用 BGE-M3 服务
            Map<String, Object> emb = null;
            if (cacheService != null) {
                emb = cacheService.getEmbedding(query);
            }
            if (emb == null) {
                emb = bgeM3.embed(query);
                if (cacheService != null && emb != null) {
                    cacheService.putEmbedding(query, emb);
                }
            }
            if (emb == null || !emb.containsKey("dense")) {
                return RagResult.EMPTY;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> sparseVec = (Map<String, Object>) emb.get("sparse");
            List<Map<String, Object>> raw = hybridSearch.hybridSearch(
                    (List<Double>) emb.get("dense"), sparseVec, filter.toString(), searchLimit,
                    query, // 原始查询文本 → 用于 BM25 关键词检索
                    0.7f, 0.2f); // Dense 0.7 + Sparse 0.2 + BM25 0.1
            long retrievalMs = System.currentTimeMillis() - start;

            if (raw.isEmpty()) {
                return RagResult.EMPTY;
            }

            // 内容去重——重叠分块可能导致相邻 chunk 内容高度相似，保留最高分条目
            raw = deduplicateByContent(raw);

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

            // 相似度阈值过滤——重排后剔除低分文档
            List<Document> filteredResults = results.stream()
                    .filter(d -> {
                        Object s = d.getMetadata().get("score");
                        return s instanceof Number && ((Number) s).doubleValue() >= similarityThreshold;
                    })
                    .collect(Collectors.toList());

            if (filteredResults.isEmpty() && !results.isEmpty()) {
                log.debug("RAG: all {} results below similarity threshold {}", results.size(), similarityThreshold);
                // 若全部低于阈值，保留最高分的一条作为兜底
                filteredResults = List.of(results.get(0));
            }

            double topScore = filteredResults.stream()
                    .mapToDouble(d -> d.getScore() != null ? d.getScore() : 0).max().orElse(0);
            double avgScore = filteredResults.stream()
                    .mapToDouble(d -> d.getScore() != null ? d.getScore() : 0).average().orElse(0);
            int hitCount = filteredResults.size();
            try {
                searchLogRepository.save(new com.dragon.agent.entity.RagSearchLog(
                        userId, query, String.join(",", accessibleKbIds),
                        hitCount, topScore, avgScore, retrievalMs, hitCount > 0));
            } catch (Exception ignored) {
                // 日志写入失败不影响检索主流程
            }

            log.debug("RAG: {} candidates -> {} reranked -> {} above threshold({}) ({}ms)",
                    candidates.size(), results.size(), hitCount, similarityThreshold, retrievalMs);
            List<Map<String, Object>> traces = buildTraces(filteredResults);
            RagResult result = new RagResult(formatContext(filteredResults), traces);

            // 缓存检索结果
            if (cacheService != null) {
                cacheService.putSearchResult(query, userId, result);
            }

            return result;
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

            // chunkIndex 可能来自 Milvus dynamic field（Long）或直接元数据（String），需要类型安全转换
            Object ciObj = doc.getMetadata().get("chunkIndex");
            if (ciObj instanceof Number) {
                trace.put("chunkIndex", ((Number) ciObj).intValue());
            } else if (ciObj instanceof String) {
                trace.put("chunkIndex", Integer.parseInt((String) ciObj));
            } else {
                trace.put("chunkIndex", 0);
            }

            Object score = doc.getMetadata().get("score");
            trace.put("score", score instanceof Number
                    ? ((Number) score).doubleValue()
                    : doc.getScore() != null ? doc.getScore() : 0.0);
            trace.put("contentSnippet", doc.getText());
            traces.add(trace);
        }
        return traces;
    }

    /**
     * 将检索结果格式化为 LLM 上下文文本，使用 Lost-in-Middle 策略重排：
     * 高相关片段放开头和结尾，中等片段放中间，避免 LLM 注意力衰减导致的中间信息丢失。
     *
     * <p>格式：每个片段带编号、文档名和相关度百分比，方便 LLM 输出引用。</p>
     */
    public String formatContext(List<Document> documents) {
        if (documents.isEmpty()) return "";

        // Lost-in-Middle 重排：高分 → 头尾，中分 → 中间
        List<Document> reordered = lostInMiddleReorder(documents);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < reordered.size(); i++) {
            Document doc = reordered.get(i);
            String name = safeGetString(doc.getMetadata(), "originalName", "未知文档");
            double score = doc.getScore() != null ? doc.getScore() : 0.0;
            int pct = (int) Math.round(score * 100);
            String chunkIdx = safeGetString(doc.getMetadata(), "chunkIndex", "0");

            sb.append("---\n");
            sb.append("[%d] **%s** (相关度: %d%%, 片段: %s)\n".formatted(i + 1, name, pct, chunkIdx));
            sb.append(doc.getText()).append("\n");
        }
        sb.append("---\n");
        return sb.toString();
    }

    /**
     * 内容去重——剔除 content 字段完全相同的条目，保留分数最高的一条。
     * 防止重叠分块导致相邻 chunk 在检索结果中重复出现。
     */
    List<Map<String, Object>> deduplicateByContent(List<Map<String, Object>> raw) {
        Map<String, Map<String, Object>> seen = new LinkedHashMap<>();
        for (var item : raw) {
            Object content = item.get("content");
            if (content == null) continue;
            String key = md5Short(content.toString());
            var existing = seen.get(key);
            if (existing == null) {
                seen.put(key, item);
            } else {
                // 保留分数更高的
                double oldScore = ((Number) existing.getOrDefault("score", 0)).doubleValue();
                double newScore = ((Number) item.getOrDefault("score", 0)).doubleValue();
                if (newScore > oldScore) {
                    seen.put(key, item);
                }
            }
        }
        if (seen.size() < raw.size()) {
            log.debug("Content dedup: {} → {} unique", raw.size(), seen.size());
        }
        return new ArrayList<>(seen.values());
    }

    /** 内容 MD5 前 16 位，用于快速去重比对 */
    static String md5Short(String content) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(digest.length, 8); i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(content.hashCode());
        }
    }

    /**
     * 类型安全地从 Map 中提取 String 值——Milvus dynamic field 可能返回 Long 而非 String。
     */
    static String safeGetString(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        if (v == null) return defaultValue;
        if (v instanceof String s) return s;
        return v.toString();
    }

    /**
     * Lost-in-Middle 重排序——将高分文档交替分布到头尾，避免关键信息落在 LLM
     * 注意力衰减的中间区域。
     *
     * <p>策略：
     * <ol>
     *   <li>文档按分数降序排列</li>
     *   <li>依次放入结果列表的头尾交替位置（第1名→头，第2名→尾，第3名→次头...）</li>
     *   <li>中等分数文档自然落在中间</li>
     * </ol>
     *
     * @param documents 按分数降序排列的文档列表
     * @return Lost-in-Middle 重排后的文档列表
     */
    List<Document> lostInMiddleReorder(List<Document> documents) {
        if (documents.size() <= 2) return new ArrayList<>(documents);

        List<Document> reordered = new ArrayList<>(documents.size());
        // 预填充 null 占位
        for (int i = 0; i < documents.size(); i++) reordered.add(null);

        int left = 0, right = documents.size() - 1;
        for (int i = 0; i < documents.size(); i++) {
            if (i % 2 == 0) {
                reordered.set(left++, documents.get(i));   // 高分 → 左边
            } else {
                reordered.set(right--, documents.get(i));  // 次高 → 右边
            }
        }
        return reordered;
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
