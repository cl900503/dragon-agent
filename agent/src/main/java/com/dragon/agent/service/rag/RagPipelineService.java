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
 * RAG 管线统一服务——会话和调试共享同一套管线逻辑，确保数据完全一致。
 *
 * <p>会话调用 {@link #execute}，调试调用 {@link #executeWithSteps}，
 * 两者内部走同一个 {@link #doExecute}，唯一区别是调试路径多了 stepCallback 回调。
 */
@Service
public class RagPipelineService {

    private static final Logger log = LoggerFactory.getLogger(RagPipelineService.class);

    @Autowired private BgeM3Client bgeM3;
    @Autowired private HybridSearchService hybridSearch;
    @Autowired private RerankService rerankService;
    @Autowired(required = false) private VectorStore vectorStore;
    @Autowired(required = false) private QueryProcessor queryProcessor;
    @Autowired private KnowledgeBaseService knowledgeBaseService;
    @Autowired private RagSearchService ragSearchService;

    /** 管线步骤数据 */
    public record PipelineStep(int step, String name, String icon, long durationMs,
            String status, String summary, Map<String, Object> detail) {}

    // ==================== 对外接口 ====================

    /** 会话用——只返回结果，无中间步骤 */
    public RagSearchService.RagResult execute(String query, Long userId) {
        return doExecute(query, userId, null);
    }

    /** 调试用——每完成一步回调一次，前端逐步渲染 */
    public RagSearchService.RagResult executeWithSteps(String query, Long userId,
            java.util.function.Consumer<PipelineStep> callback) {
        return doExecute(query, userId, callback);
    }

    // ==================== 核心管线（唯一实现） ====================

    @SuppressWarnings("unchecked")
    private RagSearchService.RagResult doExecute(String query, Long userId,
            java.util.function.Consumer<PipelineStep> callback) {
        if (vectorStore == null || userId == null) return RagSearchService.RagResult.EMPTY;

        long totalStart = now();
        List<Document> chain = List.of();
        String searchQuery = query;
        List<String> variants = List.of(query);

        // ====== 数据过滤表达式 ======
        List<String> kbIds = knowledgeBaseService.getAccessibleKbIds(userId);
        StringBuilder fb = new StringBuilder("userId == '" + userId + "'");
        if (!kbIds.isEmpty()) { fb.append(" || kbId in [");
            fb.append(kbIds.stream().map(id -> "'" + id + "'").collect(Collectors.joining(", "))); fb.append("]"); }
        final String filterExpr = fb.toString();

        // ====== Step 1: 查询改写 ======
        log.info("PIPELINE: step 1 start, query={}", query);
        long t1 = now();
        Map<String, Object> d1 = new LinkedHashMap<>();
        String intent = "未分类"; boolean rt = false, llm = false;
        if (queryProcessor != null) {
            log.info("PIPELINE: queryProcessor found, classifying...");
            var qi = queryProcessor.classify(query);
            intent = qi.name(); d1.put("intent", intent); d1.put("intentDesc", desc(qi));
            if (qi == QueryProcessor.QueryIntent.SHORT_KEYWORD || qi == QueryProcessor.QueryIntent.COMPARATIVE) {
                llm = true;
                log.info("PIPELINE: calling rewrite...");
                try { variants = queryProcessor.rewrite(query); log.info("PIPELINE: rewrite done, variants={}", variants.size()); } catch (Exception e) { rt = true; variants = List.of(query); log.warn("PIPELINE: rewrite failed", e); }
                d1.put("rewritten", variants.size() > 1); d1.put("rewriteTimedOut", rt); d1.put("llmCalled", true); d1.put("variants", variants);
                searchQuery = variants.size() > 1 ? variants.get(1) : variants.get(0);
            } else { d1.put("rewritten", false); }
        }
        if (callback != null) {
            String status = llm ? (rt ? "warning" : "success") : "info";
            String summary = variants.size() > 1 ? "分类:" + intent + " → 生成 " + (variants.size() - 1) + " 个改写变体"
                    : rt ? "分类:" + intent + " → 改写超时" : llm ? "分类:" + intent + " → 改写完成" : "分类:" + intent + "，无需改写";
            callback.accept(new PipelineStep(1, "查询改写", "🔄", now() - t1, status, summary, d1));
        }

        // ====== Step 2: 多路检索 ======
        long t2 = now();
        Map<String, Object> d2 = new LinkedHashMap<>();
        Map<String, Object> emb = bgeM3.embed(searchQuery);
        if (emb == null || !emb.containsKey("dense")) return RagSearchService.RagResult.EMPTY;

        Map<String, Object> sv = (Map<String, Object>) emb.get("sparse");
        int topK = ragSearchService.getSearchLimit();
        var searchResult = hybridSearch.search((List<Double>) emb.get("dense"), sv, filterExpr, topK, searchQuery);
        chain = searchResult.fusedResults().stream().map(r -> {
            Document d = new Document((String) r.getOrDefault("content", ""), new LinkedHashMap<>(r));
            Object s = r.get("score"); if (s instanceof Number) d.getMetadata().put("score", ((Number) s).doubleValue()); return d;
        }).collect(Collectors.toList());

        if (callback != null) {
            d2.put("Dense召回", searchResult.denseResults().size() + " 条 (COSINE, nprobe=16)");
            d2.put("Sparse召回", searchResult.sparseResults().isEmpty() ? "未启用" : searchResult.sparseResults().size() + " 条");
            d2.put("BM25召回", searchResult.bm25Results().isEmpty() ? "未启用" : searchResult.bm25Results().size() + " 条");
            d2.put("RRF融合(k=60)", searchResult.denseResults().size() + "+" + (searchResult.sparseResults().isEmpty() ? 0 : searchResult.sparseResults().size()) + "+" + (searchResult.bm25Results().isEmpty() ? 0 : searchResult.bm25Results().size()) + " → " + chain.size() + " 条");
            d2.put("filterExpr", filterExpr);
            callback.accept(new PipelineStep(2, "多路检索", "🔎", now() - t2, chain.isEmpty() ? "empty" : "success", "RRF(" + chain.size() + "条)", d2));
        }
        if (chain.isEmpty()) return RagSearchService.RagResult.EMPTY;

        // ====== Step 3: 重排序 ======
        long t3 = now();
        Map<String, Object> d3 = new LinkedHashMap<>();
        int ceInput = chain.size();
        var rr3 = rerankService.rerank(searchQuery, chain);
        chain = rr3.documents();
        for (int i = 0; i < Math.min(chain.size(), rr3.scores().size()); i++)
            chain.get(i).getMetadata().put("score", rr3.scores().get(i).score());

        if (callback != null) {
            boolean ceSkipped = ceInput <= 3;
            d3.put("候选数", ceInput + " 条");
            d3.put("MMR", "✅ 先执行 (λ=0.7, Jaccard 3-gram) → 去重后 " + chain.size() + " 条");
            d3.put("Cross-Encoder", ceInput <= 3 ? "⏭ 已跳过（候选≤3）" : "BGE-Reranker-v2-m3 → " + chain.size() + " 条");
            d3.put("最终输出", chain.size() + " 条");
            String s3 = ceInput <= 3 ? "跳过(" + ceInput + "≤3)" : "MMR(" + ceInput + "→) → CE → " + chain.size() + "条";
            callback.accept(new PipelineStep(3, "重排序", "📊", now() - t3, chain.isEmpty() ? "empty" : "success", s3, d3));
        }

        // ====== Step 4: 阈值过滤 ======
        long t4 = now();
        Map<String, Object> d4 = new LinkedHashMap<>();
        d4.put("threshold", 0.2); d4.put("beforeFilter", chain.size());
        var filtered = chain.stream().filter(d -> { Object s = d.getMetadata().get("score");
            return s instanceof Number && ((Number) s).doubleValue() >= 0.2; }).collect(Collectors.toList());
        if (filtered.isEmpty() && !chain.isEmpty()) { filtered = List.of(chain.get(0)); d4.put("fallback", "全部低于阈值"); }
        d4.put("afterFilter", filtered.size()); d4.put("removedCount", chain.size() - filtered.size());
        chain = filtered;

        if (callback != null) {
            int before = (int) d4.get("beforeFilter");
            callback.accept(new PipelineStep(4, "阈值过滤", "✂️", now() - t4, chain.size() < before ? "filtered" : "success", "阈值 0.2 → " + before + "→" + chain.size() + " 条", d4));
        }

        // ====== Step 5: 上下文构建 ======
        long t5 = now();
        String ctxt = ragSearchService.formatContext(chain);
        List<Map<String, Object>> traces = ragSearchService.buildTraces(chain);

        if (callback != null) {
            Map<String, Object> d5 = new LinkedHashMap<>();
            d5.put("lostInMiddle", true); d5.put("contentDedupApplied", true);
            d5.put("uniqueDocuments", traces.stream().map(tr -> (String) tr.get("documentName")).distinct().count());
            d5.put("contextLength", ctxt.length());
            callback.accept(new PipelineStep(5, "上下文构建", "📝", now() - t5, "success", traces.size() + " 段，" + ctxt.length() + " 字符", d5));
        }

        return new RagSearchService.RagResult(ctxt, traces);
    }

    // ==================== helpers ====================

    private long now() { return System.currentTimeMillis(); }
    private String desc(QueryProcessor.QueryIntent i) { return switch (i) {
        case SHORT_KEYWORD -> "短关键词"; case FACTUAL -> "事实查询"; case REASONING -> "推理查询"; case COMPARATIVE -> "对比查询"; }; }
}
