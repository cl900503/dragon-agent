package com.dragon.agent.service.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 查询处理器——负责查询意图分类、LLM 查询改写和多路检索编排。
 *
 * <p>处理管线：
 * <ol>
 *   <li>意图分类——基于规则快速判断查询类型，零延迟</li>
 *   <li>查询改写——对短查询/模糊查询触发 LLM 改写，生成 2-3 个检索变体</li>
 *   <li>多路检索——各变体并行检索，结果经 RRF 融合后统一排序</li>
 * </ol>
 *
 * <p>改写仅对以下情况触发：
 * <ul>
 *   <li>查询 ≤ 15 字（短查询，关键词不足）</li>
 *   <li>查询包含代词或模糊指代（"这个"、"上次那个"等）</li>
 *   <li>查询为口语化表达</li>
 * </ul>
 *
 * @author 陈龙
 * @since 2026-06-07
 */
@Service
public class QueryProcessor {

    private static final Logger log = LoggerFactory.getLogger(QueryProcessor.class);

    /** 短查询阈值（字符数），低于此值触发改写 */
    private static final int SHORT_QUERY_THRESHOLD = 15;

    /** 模糊指代词列表 */
    private static final List<String> VAGUE_PRONOUNS = List.of(
            "这个", "那个", "这些", "那些", "它", "他", "她", "它们", "他们",
            "上次", "之前", "上回", "那次", "上次那个", "前面那个",
            "怎么做", "怎么办", "怎么搞", "是什么", "啥意思", "什么意思");

    @Autowired(required = false)
    private ChatClient.Builder chatClientBuilder;

    @Autowired(required = false)
    private RewriteClient rewriteClient;

    @Autowired(required = false)
    private QueryCacheService cacheService;

    @Autowired
    private RagSearchService ragSearchService;

    /**
     * 查询意图枚举。
     */
    public enum QueryIntent {
        /** 短关键词查询（≤15 字），需改写 */
        SHORT_KEYWORD,
        /** 事实查询（15-80 字），直接检索 */
        FACTUAL,
        /** 推理/分析查询，可能需要分解 */
        REASONING,
        /** 对比查询，需要分解为子问题 */
        COMPARATIVE;
    }

    /**
     * 处理查询——意图分类 → 条件改写 → 多路检索融合。
     *
     * @param query  原始用户查询
     * @param userId 当前用户 ID
     * @return 融合后的检索结果
     */
    public RagSearchService.RagResult process(String query, Long userId) {
        QueryIntent intent = classify(query);
        log.debug("Query intent: {} for \"{}\"", intent, truncate(query, 50));

        switch (intent) {
            case SHORT_KEYWORD -> {
                // 短查询：改写 + 多路检索
                List<String> variants = rewrite(query);
                if (!variants.isEmpty()) {
                    return multiQueryRetrieve(variants, userId);
                }
            }
            case COMPARATIVE -> {
                // 对比查询：拆解子查询
                List<String> subQueries = decomposeComparison(query);
                if (!subQueries.isEmpty()) {
                    return multiQueryRetrieve(subQueries, userId);
                }
            }
            // 事实查询和推理查询：直接检索，不做额外处理
            default -> { /* fall through */ }
        }

        // 兜底：直接检索
        return ragSearchService.retrieveContext(query, userId);
    }

    /**
     * 规则驱动的查询意图分类（零延迟，无 LLM 调用）。
     */
    QueryIntent classify(String query) {
        if (query == null || query.isBlank()) {
            return QueryIntent.SHORT_KEYWORD;
        }

        String q = query.trim();

        // 模糊指代检测
        for (String pronoun : VAGUE_PRONOUNS) {
            if (q.contains(pronoun)) {
                return QueryIntent.SHORT_KEYWORD;
            }
        }

        // 对比检测
        if (containsAny(q, List.of("对比", "比较", "区别", "差异", " vs ", "和", "与"))
                && q.length() > 8) {
            return QueryIntent.COMPARATIVE;
        }

        // 推理/分析检测
        if (containsAny(q, List.of("为什么", "如何", "怎么", "原因", "分析", "是否应该"))
                && q.length() > 10) {
            return QueryIntent.REASONING;
        }

        // 长度判断
        if (q.length() <= SHORT_QUERY_THRESHOLD) {
            return QueryIntent.SHORT_KEYWORD;
        }

        return QueryIntent.FACTUAL;
    }

    /**
     * LLM 驱动的查询改写——生成 2-3 个检索优化后的变体查询。
     *
     * <p>改写策略：
     * <ul>
     *   <li>原始查询直接作为第一个变体</li>
     *   <li>LLM 生成 1-2 个补充变体（展开缩写、补充上下文、规范化表述）</li>
     * </ul>
     */
    List<String> rewrite(String query) {
        // 改写缓存——相同查询不重复调 LLM
        if (cacheService != null) {
            var cached = cacheService.getRewriteResult(query);
            if (cached != null) return cached;
        }

        // RewriteClient 内置了 RestTemplate 超时（connect=3s, read=5s）
        if (rewriteClient != null) {
            try {
                String response = rewriteClient.rewrite(query);
                if (response != null && !response.isBlank()) {
                    List<String> variants = new ArrayList<>();
                    for (String line : response.split("\n")) {
                        String trimmed = line.trim();
                        if (!trimmed.isBlank() && variants.size() < 3) variants.add(trimmed);
                    }
                    log.debug("Query rewritten (V3): \"{}\" → {} variants in {}ms",
                            truncate(query, 30), variants.size(), 0);
                    if (cacheService != null) cacheService.putRewriteResult(query, variants);
                    return variants.isEmpty() ? List.of(query) : variants;
                }
            } catch (Exception e) {
                log.warn("RewriteClient failed: {}", e.getMessage());
            }
            return List.of(query);
        }

        return List.of(query);
    }

    /**
     * 对比查询拆解——将"对比 A 和 B"拆为"A"和"B"两个子查询。
     */
    List<String> decomposeComparison(String query) {
        List<String> subQueries = new ArrayList<>();
        subQueries.add(query); // 保留原始查询

        // 简单启发式：按"和"、"与"、" vs "拆分
        for (String sep : List.of(" vs ", " VS ")) {
            if (query.contains(sep)) {
                String[] parts = query.split(sep, 2);
                if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                    subQueries.add(parts[0].trim());
                    subQueries.add(parts[1].trim());
                    return subQueries;
                }
            }
        }
        return subQueries;
    }

    /**
     * 多查询变体并行检索——各变体独立检索后用 RRF 融合排序。
     *
     * <p>RRF（Reciprocal Rank Fusion）公式：
     * score(d) = Σ 1/(k + rank_i(d))，其中 k=60
     */
    RagSearchService.RagResult multiQueryRetrieve(List<String> queries, Long userId) {
        if (queries.isEmpty()) return RagSearchService.RagResult.EMPTY;

        // 所有变体并行检索
        List<RagSearchService.RagResult> allResults = new ArrayList<>();
        for (String q : queries) {
            var result = ragSearchService.retrieveContext(q, userId);
            if (!result.isEmpty()) {
                allResults.add(result);
            }
        }

        if (allResults.isEmpty()) return RagSearchService.RagResult.EMPTY;
        if (allResults.size() == 1) return allResults.get(0);

        // RRF 融合：将各路的 traces 按文档名+chunkIndex 去重并重新排序
        return mergeByRRF(allResults);
    }

    /**
     * 基于简化 RRF 的多路检索结果融合。
     *
     * <p>对相同（documentName, chunkIndex）的 trace 保留最高分，最后按分数降序返回。
     */
    private RagSearchService.RagResult mergeByRRF(List<RagSearchService.RagResult> results) {
        // key: documentName + "::" + chunkIndex, value: best trace
        Map<String, Map<String, Object>> mergedTraces = new LinkedHashMap<>();
        Map<String, String> traceToContext = new LinkedHashMap<>();

        for (var result : results) {
            for (var trace : result.traces()) {
                String docName = (String) trace.getOrDefault("documentName", "未知");
                Object chunkIdx = trace.get("chunkIndex");
                String key = docName + "::" + (chunkIdx != null ? chunkIdx.toString() : "0");

                Object score = trace.get("score");
                double newScore = score instanceof Number ? ((Number) score).doubleValue() : 0;
                double oldScore = 0;
                var existing = mergedTraces.get(key);
                if (existing != null && existing.get("score") instanceof Number) {
                    oldScore = ((Number) existing.get("score")).doubleValue();
                }

                if (existing == null || newScore > oldScore) {
                    mergedTraces.put(key, new LinkedHashMap<>(trace));
                    // 也保留最高分对应的 snippet
                    traceToContext.put(key, (String) trace.getOrDefault("contentSnippet", ""));
                }
            }
        }

        // 按分数降序排列
        List<Map<String, Object>> sortedTraces = mergedTraces.values().stream()
                .sorted((a, b) -> {
                    double sa = a.get("score") instanceof Number ? ((Number) a.get("score")).doubleValue() : 0;
                    double sb = b.get("score") instanceof Number ? ((Number) b.get("score")).doubleValue() : 0;
                    return Double.compare(sb, sa);
                })
                .collect(Collectors.toList());

        // 重建 context 文本
        StringBuilder ctx = new StringBuilder();
        int idx = 1;
        for (var trace : sortedTraces) {
            String name = (String) trace.getOrDefault("documentName", "未知");
            String content = (String) trace.getOrDefault("contentSnippet", "");
            double score = trace.get("score") instanceof Number
                    ? ((Number) trace.get("score")).doubleValue() : 0;
            int pct = (int) Math.round(score * 100);
            ctx.append("---\n[%d] **%s** (相关度: %d%%)\n%s\n".formatted(idx++, name, pct, content));
        }
        if (!sortedTraces.isEmpty()) ctx.append("---\n");

        log.debug("Multi-query RRF: {} variants → {} unique results", results.size(), sortedTraces.size());
        return new RagSearchService.RagResult(ctx.toString(), sortedTraces);
    }

    private static boolean containsAny(String text, List<String> keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
