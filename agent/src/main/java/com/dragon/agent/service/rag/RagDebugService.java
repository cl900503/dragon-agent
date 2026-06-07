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
import org.springframework.stereotype.Service;

import com.dragon.agent.service.KnowledgeBaseService;

/**
 * RAG 管线调试服务——逐步执行检索管线并收集每步的中间结果。
 *
 * <p>供前端语义检索调试页使用，展示 5 步管线的完整执行过程：
 * <ol>
 *   <li>查询改写 —— 意图分类 + LLM 改写变体</li>
 *   <li>多路检索 —— Dense + Sparse + BM25 三路召回 + RRF 融合</li>
 *   <li>重排序 —— Cross-Encoder + MMR 多样性去重</li>
 *   <li>阈值过滤 —— similarity-threshold 过滤低分文档</li>
 *   <li>上下文构建 —— Lost-in-Middle 重排 + 结构化引用</li>
 * </ol>
 *
 * @author 陈龙
 * @since 2026-06-07
 */
@Service
public class RagDebugService {

    private static final Logger log = LoggerFactory.getLogger(RagDebugService.class);

    @Autowired
    private BgeM3Client bgeM3;

    @Autowired
    private HybridSearchService hybridSearch;

    @Autowired
    private RerankService rerankService;

    @Autowired(required = false)
    private VectorStore vectorStore;

    @Autowired(required = false)
    private QueryProcessor queryProcessor;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private RagSearchService ragSearchService;

    /**
     * 调试结果——包含所有步骤的中间数据和最终结果。
     */
    public record DebugResult(
            String query,
            long totalMs,
            List<PipelineStep> steps,
            List<Map<String, Object>> finalTraces,
            String finalContext,
            int finalCount) {
    }

    /**
     * 管线单步记录。
     */
    public record PipelineStep(
            int step,
            String name,
            String icon,
            long durationMs,
            String status,     // "success" | "warning" | "empty"
            String summary,
            Map<String, Object> detail) {
    }

    /**
     * 执行完整的 RAG 管线并收集每步的调试信息。
     */
    @SuppressWarnings("unchecked")
    public DebugResult debug(String query, Long userId) {
        try {
            return doDebug(query, userId);
        } catch (Exception e) {
            log.error("RAG debug failed: {}", e.getMessage(), e);
            List<PipelineStep> steps = new ArrayList<>();
            steps.add(new PipelineStep(0, "错误", "❌", 0, "error", e.getMessage(), Map.of("exception", e.getClass().getName())));
            return new DebugResult(query, 0, steps, List.of(), "", 0);
        }
    }

    private DebugResult doDebug(String query, Long userId) {
        long totalStart = System.currentTimeMillis();
        List<PipelineStep> steps = new ArrayList<>();

        if (vectorStore == null || userId == null) {
            return new DebugResult(query, System.currentTimeMillis() - totalStart, steps, List.of(), "", 0);
        }

        // ======  Step 1: 查询改写 ======
        long t1 = System.currentTimeMillis();
        Map<String, Object> step1Detail = new LinkedHashMap<>();
        String searchQuery = query;
        List<String> variants = List.of(query);

        String intent = "未分类";
        boolean rewriteTimedOut = false;
        boolean llmCalled = false;
        if (queryProcessor != null) {
            var qi = queryProcessor.classify(query);
            intent = qi.name();
            step1Detail.put("intent", intent);
            step1Detail.put("intentDesc", describeIntent(qi));

            if (qi == QueryProcessor.QueryIntent.SHORT_KEYWORD || qi == QueryProcessor.QueryIntent.COMPARATIVE) {
                llmCalled = true;
                try {
                    var future = java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> queryProcessor.rewrite(query));
                    variants = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("Query rewrite timeout or failed: {}", e.getMessage());
                    rewriteTimedOut = true;
                    variants = List.of(query);
                }
                boolean actuallyRewritten = variants.size() > 1;
                step1Detail.put("rewritten", actuallyRewritten);
                step1Detail.put("rewriteTimedOut", rewriteTimedOut);
                step1Detail.put("variants", variants);
                searchQuery = actuallyRewritten ? variants.get(1) : variants.get(0);
            } else {
                step1Detail.put("rewritten", false);
            }
        } else {
            step1Detail.put("rewritten", false);
            step1Detail.put("note", "QueryProcessor 未注入");
        }

        long step1Ms = System.currentTimeMillis() - t1;
        String step1Status = llmCalled ? (rewriteTimedOut ? "warning" : "success") : "info";
        String step1Summary;
        if (variants.size() > 1) {
            step1Summary = "分类: " + intent + " → 生成 " + (variants.size() - 1) + " 个改写变体";
        } else if (rewriteTimedOut) {
            step1Summary = "分类: " + intent + " → 改写超时，使用原查询";
        } else if (llmCalled) {
            step1Summary = "分类: " + intent + " → 改写完成，使用原查询";
        } else {
            step1Summary = "分类: " + intent + "，无需改写";
        }
        steps.add(new PipelineStep(1, "查询改写", "🔄", step1Ms, step1Status, step1Summary, step1Detail));

        // ====== Step 2: 多路检索 ======
        long t2 = System.currentTimeMillis();
        Map<String, Object> step2Detail = new LinkedHashMap<>();

        // Embedding
        Map<String, Object> emb = bgeM3.embed(searchQuery);
        if (emb == null || !emb.containsKey("dense")) {
            steps.add(new PipelineStep(2, "多路检索", "🔎", System.currentTimeMillis() - t2,
                    "error", "Embedding 失败", Map.of("error", "BGE-M3 返回空")));
            return new DebugResult(query, System.currentTimeMillis() - totalStart, steps, List.of(), "", 0);
        }

        // 数据过滤
        List<String> accessibleKbIds = knowledgeBaseService.getAccessibleKbIds(userId);
        StringBuilder filter = new StringBuilder("userId == '" + userId + "'");
        if (!accessibleKbIds.isEmpty()) {
            filter.append(" || kbId in [");
            filter.append(accessibleKbIds.stream().map(id -> "'" + id + "'").collect(Collectors.joining(", ")));
            filter.append("]");
        }
        String filterExpr = filter.toString();
        step2Detail.put("filterExpr", filterExpr);

        // 三路检索
        Map<String, Object> sparseVec = (Map<String, Object>) emb.get("sparse");
        List<Double> denseVec = (List<Double>) emb.get("dense");
        int topK = 20;

        var vectorResults = hybridSearch.hybridSearch(denseVec, sparseVec, filterExpr, topK, searchQuery, 0.7f, 0.2f);

        // 统计各路结果（从 hybridSearch 无法直接拿到分路数据，做近似估算）
        step2Detail.put("denseVectorDim", denseVec.size());
        step2Detail.put("sparseAvailable", sparseVec != null && sparseVec.containsKey("indices"));
        step2Detail.put("fusionMethod", "WeightedRanker(Dense 0.7 + Sparse 0.2) + RRF(BM25 0.1)");
        step2Detail.put("candidatesAfterFusion", vectorResults.size());
        step2Detail.put("topKCandidates", Math.min(vectorResults.size(), topK));

        if (!vectorResults.isEmpty()) {
            double topScore = vectorResults.stream()
                    .mapToDouble(r -> ((Number) r.getOrDefault("score", 0)).doubleValue()).max().orElse(0);
            step2Detail.put("topScoreAfterFusion", String.format("%.4f", topScore));
        }

        long step2Ms = System.currentTimeMillis() - t2;
        steps.add(new PipelineStep(2, "多路检索", "🔎", step2Ms,
                vectorResults.isEmpty() ? "empty" : "success",
                "Dense + Sparse + BM25 → RRF 融合 → " + vectorResults.size() + " 条候选",
                step2Detail));

        if (vectorResults.isEmpty()) {
            return new DebugResult(query, System.currentTimeMillis() - totalStart, steps, List.of(), "", 0);
        }

        // ====== Step 3: 重排序 ======
        long t3 = System.currentTimeMillis();
        Map<String, Object> step3Detail = new LinkedHashMap<>();

        List<Document> candidates = vectorResults.stream().map(r -> {
            Document d = new Document((String) r.getOrDefault("content", ""), new LinkedHashMap<>(r));
            Object s = r.get("score");
            if (s instanceof Number) {
                d.getMetadata().put("score", ((Number) s).doubleValue());
            }
            return d;
        }).collect(Collectors.toList());

        var rerankResult = rerankService.rerank(searchQuery, candidates);
        List<Document> results = rerankResult.documents();
        step3Detail.put("candidatesBeforeRerank", candidates.size());
        step3Detail.put("crossEncoderModel", "BGE-Reranker-v2-m3");
        step3Detail.put("afterCrossEncoder", results.size());
        step3Detail.put("mmrEnabled", true);
        step3Detail.put("mmrLambda", 0.7);

        if (!results.isEmpty()) {
            double topScore = results.stream().mapToDouble(d -> {
                Object s = d.getMetadata().get("score");
                return s instanceof Number ? ((Number) s).doubleValue() : 0;
            }).max().orElse(0);
            step3Detail.put("topRerankScore", String.format("%.4f", topScore));
        }

        for (int i = 0; i < Math.min(results.size(), rerankResult.scores().size()); i++) {
            results.get(i).getMetadata().put("score", rerankResult.scores().get(i).score());
        }

        long step3Ms = System.currentTimeMillis() - t3;
        steps.add(new PipelineStep(3, "重排序", "📊", step3Ms,
                results.isEmpty() ? "empty" : "success",
                "Cross-Encoder + MMR → " + candidates.size() + "→" + results.size() + " 条",
                step3Detail));

        // ====== Step 4: 阈值过滤 ======
        long t4 = System.currentTimeMillis();
        Map<String, Object> step4Detail = new LinkedHashMap<>();
        double threshold = 0.2;
        step4Detail.put("threshold", threshold);

        List<Document> filteredResults = results.stream()
                .filter(d -> {
                    Object s = d.getMetadata().get("score");
                    return s instanceof Number && ((Number) s).doubleValue() >= threshold;
                })
                .collect(Collectors.toList());

        step4Detail.put("beforeFilter", results.size());
        step4Detail.put("afterFilter", filteredResults.size());
        step4Detail.put("removedCount", results.size() - filteredResults.size());

        if (filteredResults.isEmpty() && !results.isEmpty()) {
            filteredResults = List.of(results.get(0));
            step4Detail.put("fallback", "全部低于阈值，保留最高分兜底");
        }

        long step4Ms = System.currentTimeMillis() - t4;
        steps.add(new PipelineStep(4, "阈值过滤", "✂️", step4Ms,
                filteredResults.size() < results.size() ? "filtered" : "success",
                "阈值 " + threshold + " → " + results.size() + "→" + filteredResults.size() + " 条",
                step4Detail));

        // ====== Step 5: 上下文构建 ======
        long t5 = System.currentTimeMillis();
        Map<String, Object> step5Detail = new LinkedHashMap<>();

        String context = ragSearchService.formatContext(filteredResults);
        List<Map<String, Object>> traces = ragSearchService.buildTraces(filteredResults);

        step5Detail.put("lostInMiddle", true);
        step5Detail.put("contentDedupApplied", true);
        step5Detail.put("uniqueDocuments", traces.stream().map(t -> (String) t.get("documentName")).distinct().count());
        step5Detail.put("contextLength", context.length());

        long step5Ms = System.currentTimeMillis() - t5;
        steps.add(new PipelineStep(5, "上下文构建", "📝", step5Ms,
                "success",
                "Lost-in-Middle + 去重 → " + traces.size() + " 段，共 " + context.length() + " 字符",
                step5Detail));

        long totalMs = System.currentTimeMillis() - totalStart;
        return new DebugResult(query, totalMs, steps, traces, context, traces.size());
    }

    private String describeIntent(QueryProcessor.QueryIntent intent) {
        return switch (intent) {
            case SHORT_KEYWORD -> "短关键词（≤15字或含模糊指代），触发改写";
            case FACTUAL -> "事实查询，直接检索";
            case REASONING -> "推理/分析查询，直接检索";
            case COMPARATIVE -> "对比查询，拆解子查询";
        };
    }
}
