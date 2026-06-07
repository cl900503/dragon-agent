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

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

@Service
public class RagDebugService {

    private static final Logger log = LoggerFactory.getLogger(RagDebugService.class);

    @Autowired private BgeM3Client bgeM3;
    @Autowired private HybridSearchService hybridSearch;
    @Autowired private RerankService rerankService;
    @Autowired(required = false) private VectorStore vectorStore;
    @Autowired(required = false) private QueryProcessor queryProcessor;
    @Autowired private KnowledgeBaseService knowledgeBaseService;
    @Autowired private RagSearchService ragSearchService;

    public record DebugResult(String query, long totalMs, List<PipelineStep> steps,
            List<Map<String, Object>> finalTraces, String finalContext, int finalCount) {}
    public record PipelineStep(int step, String name, String icon, long durationMs,
            String status, String summary, Map<String, Object> detail) {}

    /** 同步一次性调试 */
    public DebugResult debug(String query, Long userId) {
        try { return doDebug(query, userId); } catch (Exception e) {
            log.error("RAG debug failed", e);
            return new DebugResult(query, 0, List.of(new PipelineStep(0, "错误", "❌", 0, "error", e.getMessage(), Map.of())), List.of(), "", 0);
        }
    }

    /** 流式 SSE 调试——每个 step 实时推送 */
    @SuppressWarnings("unchecked")
    public Flux<Map<String, Object>> debugStream(String query, Long userId) {
        return Flux.create(sink -> {
            Thread t = new Thread(() -> {
                try {
                    long totalStart = now();
                    if (vectorStore == null || userId == null) { sink.next(error("向量存储或用户未就绪")); sink.complete(); return; }

                    List<String> kbIds = knowledgeBaseService.getAccessibleKbIds(userId);
                    StringBuilder fb = new StringBuilder("userId == '" + userId + "'");
                    if (!kbIds.isEmpty()) { fb.append(" || kbId in ["); fb.append(kbIds.stream().map(id -> "'" + id + "'").collect(Collectors.joining(", "))); fb.append("]"); }
                    String filterExpr = fb.toString();
                    String searchQuery = query;
                    List<Document> chain = List.of();

                    // Step 1: 查询改写
                    long t1 = now(); Map<String, Object> d1 = new LinkedHashMap<>();
                    List<String> variants = List.of(query); String intent = "未分类"; boolean rt = false, llm = false;
                    if (queryProcessor != null) { var qi = queryProcessor.classify(query); intent = qi.name(); d1.put("intent", intent); d1.put("intentDesc", desc(qi));
                        if (qi == QueryProcessor.QueryIntent.SHORT_KEYWORD || qi == QueryProcessor.QueryIntent.COMPARATIVE) { llm = true;
                            try { var f = java.util.concurrent.CompletableFuture.supplyAsync(() -> queryProcessor.rewrite(query)); variants = f.get(5, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { rt = true; variants = List.of(query); }
                            d1.put("rewritten", variants.size() > 1); d1.put("rewriteTimedOut", rt); d1.put("llmCalled", true); d1.put("variants", variants);
                            searchQuery = variants.size() > 1 ? variants.get(1) : variants.get(0); } else { d1.put("rewritten", false); } }
                    sink.next(step(1, "查询改写", "🔄", now() - t1, llm ? (rt ? "warning" : "success") : "info",
                            variants.size() > 1 ? "分类:" + intent + " → 生成 " + (variants.size() - 1) + " 个改写变体" : rt ? "分类:" + intent + " → 改写超时" : llm ? "分类:" + intent + " → 改写完成" : "分类:" + intent + "，无需改写", d1));
                    Thread.sleep(80);

                    // Step 2: 多路检索
                    long t2 = now(); Map<String, Object> d2 = new LinkedHashMap<>();
                    Map<String, Object> emb = bgeM3.embed(searchQuery);
                    if (emb == null || !emb.containsKey("dense")) { sink.next(error("Embedding 失败")); sink.complete(); return; }
                    d2.put("denseVectorDim", ((List<Double>) emb.get("dense")).size()); d2.put("sparseAvailable", emb.containsKey("sparse"));
                    d2.put("fusionMethod", "WeightedRanker(Dense 0.7+Sparse 0.2)+RRF(BM25 0.1)"); d2.put("filterExpr", filterExpr);
                    Map<String, Object> sv = (Map<String, Object>) emb.get("sparse");
                    var vr = hybridSearch.hybridSearch((List<Double>) emb.get("dense"), sv, filterExpr, 20, searchQuery, 0.7f, 0.2f);
                    d2.put("candidatesAfterFusion", vr.size());
                    chain = vr.stream().map(r -> { Document d = new Document((String) r.getOrDefault("content", ""), new LinkedHashMap<>(r)); Object s = r.get("score"); if (s instanceof Number) d.getMetadata().put("score", ((Number) s).doubleValue()); return d; }).collect(Collectors.toList());
                    sink.next(step(2, "多路检索", "🔎", now() - t2, vr.isEmpty() ? "empty" : "success", "Dense+Sparse+BM25 → RRF融合 → " + vr.size() + " 条候选", d2));
                    Thread.sleep(80);
                    if (vr.isEmpty()) { sink.next(finalEvt(query, now() - totalStart, List.of(), "", 0)); sink.complete(); return; }

                    // Step 3: 重排序
                    long t3 = now(); Map<String, Object> d3 = new LinkedHashMap<>();
                    d3.put("candidatesBeforeRerank", chain.size()); d3.put("crossEncoderModel", "BGE-Reranker-v2-m3"); d3.put("mmrEnabled", true); d3.put("mmrLambda", 0.7);
                    var rr3 = rerankService.rerank(searchQuery, chain); chain = rr3.documents();
                    for (int i = 0; i < Math.min(chain.size(), rr3.scores().size()); i++) chain.get(i).getMetadata().put("score", rr3.scores().get(i).score());
                    d3.put("afterCrossEncoder", chain.size());
                    sink.next(step(3, "重排序", "📊", now() - t3, chain.isEmpty() ? "empty" : "success", "Cross-Encoder+MMR → " + d3.get("candidatesBeforeRerank") + "→" + chain.size() + " 条", d3));
                    Thread.sleep(80);

                    // Step 4: 阈值过滤
                    long t4 = now(); Map<String, Object> d4 = new LinkedHashMap<>(); d4.put("threshold", 0.2); d4.put("beforeFilter", chain.size());
                    var f4 = chain.stream().filter(d -> { Object s = d.getMetadata().get("score"); return s instanceof Number && ((Number) s).doubleValue() >= 0.2; }).collect(Collectors.toList());
                    if (f4.isEmpty() && !chain.isEmpty()) { f4 = List.of(chain.get(0)); d4.put("fallback", "全部低于阈值"); }
                    d4.put("afterFilter", f4.size()); d4.put("removedCount", chain.size() - f4.size()); chain = f4;
                    sink.next(step(4, "阈值过滤", "✂️", now() - t4, f4.size() < (int) d4.get("beforeFilter") ? "filtered" : "success", "阈值 0.2 → " + d4.get("beforeFilter") + "→" + f4.size() + " 条", d4));
                    Thread.sleep(80);

                    // Step 5: 上下文构建
                    long t5 = now(); Map<String, Object> d5 = new LinkedHashMap<>();
                    String ctxt = ragSearchService.formatContext(chain); List<Map<String, Object>> traces = ragSearchService.buildTraces(chain);
                    d5.put("lostInMiddle", true); d5.put("contentDedupApplied", true); d5.put("uniqueDocuments", traces.stream().map(tr -> (String) tr.get("documentName")).distinct().count()); d5.put("contextLength", ctxt.length());
                    sink.next(step(5, "上下文构建", "📝", now() - t5, "success", "Lost-in-Middle+去重 → " + traces.size() + " 段，共 " + ctxt.length() + " 字符", d5));
                    Thread.sleep(80);

                    sink.next(finalEvt(query, now() - totalStart, traces, ctxt, traces.size()));
                } catch (InterruptedException e) { Thread.currentThread().interrupt();
                } catch (Exception e) { log.error("debugStream failed", e); sink.next(error(e.getClass().getSimpleName() + ": " + e.getMessage())); }
                sink.complete();
            }, "rag-debug-stream");
            t.setDaemon(true);
            t.start();
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    /** 分步回调（轮询用） */
    @SuppressWarnings("unchecked")
    public void debugStepByStep(String query, Long userId, java.util.function.Consumer<PipelineStep> cb, java.util.function.Consumer<DebugResult> done) {
        try {
            long totalStart = now();
            if (vectorStore == null || userId == null) return;
            List<String> kbIds = knowledgeBaseService.getAccessibleKbIds(userId);
            StringBuilder fb = new StringBuilder("userId == '" + userId + "'");
            if (!kbIds.isEmpty()) { fb.append(" || kbId in ["); fb.append(kbIds.stream().map(id -> "'" + id + "'").collect(Collectors.joining(", "))); fb.append("]"); }
            String filterExpr = fb.toString(); String searchQuery = query; List<Document> chain = List.of();

            long t1 = now(); Map<String, Object> d1 = new LinkedHashMap<>();
            List<String> variants = List.of(query); String intent = "未分类"; boolean rt = false, llm = false;
            if (queryProcessor != null) { var qi = queryProcessor.classify(query); intent = qi.name(); d1.put("intent", intent); d1.put("intentDesc", desc(qi));
                if (qi == QueryProcessor.QueryIntent.SHORT_KEYWORD || qi == QueryProcessor.QueryIntent.COMPARATIVE) { llm = true;
                    try { var f = java.util.concurrent.CompletableFuture.supplyAsync(() -> queryProcessor.rewrite(query)); variants = f.get(5, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { rt = true; variants = List.of(query); }
                    d1.put("rewritten", variants.size() > 1); d1.put("rewriteTimedOut", rt); d1.put("llmCalled", true); d1.put("variants", variants);
                    searchQuery = variants.size() > 1 ? variants.get(1) : variants.get(0); } else { d1.put("rewritten", false); } }
            cb.accept(new PipelineStep(1, "查询改写", "🔄", now() - t1, llm ? (rt ? "warning" : "success") : "info", variants.size() > 1 ? "分类:" + intent + " → 生成 " + (variants.size() - 1) + " 个改写变体" : rt ? "分类:" + intent + " → 改写超时" : llm ? "分类:" + intent + " → 改写完成" : "分类:" + intent + "，无需改写", d1));

            long t2 = now(); Map<String, Object> d2 = new LinkedHashMap<>();
            Map<String, Object> emb = bgeM3.embed(searchQuery);
            if (emb == null || !emb.containsKey("dense")) { done.accept(new DebugResult(query, now() - totalStart, List.of(), List.of(), "", 0)); return; }
            d2.put("denseVectorDim", ((List<Double>) emb.get("dense")).size()); d2.put("sparseAvailable", emb.containsKey("sparse")); d2.put("fusionMethod", "WeightedRanker(Dense 0.7+Sparse 0.2)+RRF(BM25 0.1)"); d2.put("filterExpr", filterExpr);
            Map<String, Object> sv = (Map<String, Object>) emb.get("sparse");
            var vr = hybridSearch.hybridSearch((List<Double>) emb.get("dense"), sv, filterExpr, 20, searchQuery, 0.7f, 0.2f); d2.put("candidatesAfterFusion", vr.size());
            chain = vr.stream().map(r -> { Document d = new Document((String) r.getOrDefault("content", ""), new LinkedHashMap<>(r)); Object s = r.get("score"); if (s instanceof Number) d.getMetadata().put("score", ((Number) s).doubleValue()); return d; }).collect(Collectors.toList());
            cb.accept(new PipelineStep(2, "多路检索", "🔎", now() - t2, vr.isEmpty() ? "empty" : "success", "Dense+Sparse+BM25 → RRF融合 → " + vr.size() + " 条候选", d2));
            if (vr.isEmpty()) { done.accept(new DebugResult(query, now() - totalStart, List.of(), List.of(), "", 0)); return; }

            long t3 = now(); Map<String, Object> d3 = new LinkedHashMap<>(); d3.put("candidatesBeforeRerank", chain.size()); d3.put("crossEncoderModel", "BGE-Reranker-v2-m3"); d3.put("mmrEnabled", true); d3.put("mmrLambda", 0.7);
            var rr3 = rerankService.rerank(searchQuery, chain); chain = rr3.documents();
            for (int i = 0; i < Math.min(chain.size(), rr3.scores().size()); i++) chain.get(i).getMetadata().put("score", rr3.scores().get(i).score()); d3.put("afterCrossEncoder", chain.size());
            cb.accept(new PipelineStep(3, "重排序", "📊", now() - t3, chain.isEmpty() ? "empty" : "success", "Cross-Encoder+MMR → " + d3.get("candidatesBeforeRerank") + "→" + chain.size() + " 条", d3));

            long t4 = now(); Map<String, Object> d4 = new LinkedHashMap<>(); d4.put("threshold", 0.2); d4.put("beforeFilter", chain.size());
            var f4 = chain.stream().filter(d -> { Object s = d.getMetadata().get("score"); return s instanceof Number && ((Number) s).doubleValue() >= 0.2; }).collect(Collectors.toList());
            if (f4.isEmpty() && !chain.isEmpty()) { f4 = List.of(chain.get(0)); d4.put("fallback", "全部低于阈值"); } d4.put("afterFilter", f4.size()); d4.put("removedCount", chain.size() - f4.size()); chain = f4;
            cb.accept(new PipelineStep(4, "阈值过滤", "✂️", now() - t4, f4.size() < (int) d4.get("beforeFilter") ? "filtered" : "success", "阈值 0.2 → " + d4.get("beforeFilter") + "→" + f4.size() + " 条", d4));

            long t5 = now(); Map<String, Object> d5 = new LinkedHashMap<>();
            String ctxt = ragSearchService.formatContext(chain); List<Map<String, Object>> traces = ragSearchService.buildTraces(chain);
            d5.put("lostInMiddle", true); d5.put("contentDedupApplied", true); d5.put("uniqueDocuments", traces.stream().map(tr -> (String) tr.get("documentName")).distinct().count()); d5.put("contextLength", ctxt.length());
            cb.accept(new PipelineStep(5, "上下文构建", "📝", now() - t5, "success", "Lost-in-Middle+去重 → " + traces.size() + " 段，共 " + ctxt.length() + " 字符", d5));

            done.accept(new DebugResult(query, now() - totalStart, List.of(), traces, ctxt, traces.size()));
        } catch (Exception e) { log.error("debugStepByStep failed", e); }
    }

    // ========== helpers ==========

    private long now() { return System.currentTimeMillis(); }
    private String desc(QueryProcessor.QueryIntent i) { return switch (i) { case SHORT_KEYWORD -> "短关键词"; case FACTUAL -> "事实查询"; case REASONING -> "推理查询"; case COMPARATIVE -> "对比查询"; }; }
    private static Map<String, Object> step(int s, String n, String ic, long ms, String st, String sum, Map<String, Object> d) { Map<String, Object> e = new LinkedHashMap<>(); e.put("type", "step"); e.put("step", s); e.put("name", n); e.put("icon", ic); e.put("durationMs", ms); e.put("status", st); e.put("summary", sum); e.put("detail", d); return e; }
    private static Map<String, Object> finalEvt(String q, long ms, List<Map<String, Object>> tr, String ctx, int cnt) { Map<String, Object> e = new LinkedHashMap<>(); e.put("type", "final"); e.put("query", q); e.put("totalMs", ms); e.put("finalTraces", tr); e.put("finalContext", ctx); e.put("finalCount", cnt); return e; }
    private static Map<String, Object> error(String msg) { Map<String, Object> e = new LinkedHashMap<>(); e.put("type", "error"); e.put("error", msg); return e; }

    // ========== 同步 debug 内部实现 ==========

    @SuppressWarnings("unchecked")
    private DebugResult doDebug(String query, Long userId) {
        long totalStart = now();
        if (vectorStore == null || userId == null) return new DebugResult(query, 0, List.of(), List.of(), "", 0);
        List<PipelineStep> steps = new ArrayList<>();

        List<String> kbIds = knowledgeBaseService.getAccessibleKbIds(userId);
        StringBuilder fb = new StringBuilder("userId == '" + userId + "'");
        if (!kbIds.isEmpty()) { fb.append(" || kbId in ["); fb.append(kbIds.stream().map(id -> "'" + id + "'").collect(Collectors.joining(", "))); fb.append("]"); }
        String filterExpr = fb.toString(); String searchQuery = query; List<Document> chain = List.of();

        long t1 = now(); Map<String, Object> d1 = new LinkedHashMap<>();
        List<String> variants = List.of(query); String intent = "未分类"; boolean rt = false, llm = false;
        if (queryProcessor != null) { var qi = queryProcessor.classify(query); intent = qi.name(); d1.put("intent", intent); d1.put("intentDesc", desc(qi));
            if (qi == QueryProcessor.QueryIntent.SHORT_KEYWORD || qi == QueryProcessor.QueryIntent.COMPARATIVE) { llm = true;
                try { var f = java.util.concurrent.CompletableFuture.supplyAsync(() -> queryProcessor.rewrite(query)); variants = f.get(5, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception e) { rt = true; variants = List.of(query); }
                d1.put("rewritten", variants.size() > 1); d1.put("rewriteTimedOut", rt); d1.put("llmCalled", true); d1.put("variants", variants);
                searchQuery = variants.size() > 1 ? variants.get(1) : variants.get(0); } else { d1.put("rewritten", false); } }
        steps.add(new PipelineStep(1, "查询改写", "🔄", now() - t1, llm ? (rt ? "warning" : "success") : "info", variants.size() > 1 ? "分类:" + intent + " → 生成 " + (variants.size() - 1) + " 个改写变体" : rt ? "分类:" + intent + " → 改写超时" : llm ? "分类:" + intent + " → 改写完成" : "分类:" + intent + "，无需改写", d1));

        long t2 = now(); Map<String, Object> d2 = new LinkedHashMap<>();
        Map<String, Object> emb = bgeM3.embed(searchQuery);
        if (emb == null || !emb.containsKey("dense")) return new DebugResult(query, now() - totalStart, steps, List.of(), "", 0);
        d2.put("denseVectorDim", ((List<Double>) emb.get("dense")).size()); d2.put("sparseAvailable", emb.containsKey("sparse")); d2.put("fusionMethod", "WeightedRanker(Dense 0.7+Sparse 0.2)+RRF(BM25 0.1)"); d2.put("filterExpr", filterExpr);
        Map<String, Object> sv = (Map<String, Object>) emb.get("sparse");
        var vr = hybridSearch.hybridSearch((List<Double>) emb.get("dense"), sv, filterExpr, 20, searchQuery, 0.7f, 0.2f); d2.put("candidatesAfterFusion", vr.size());
        chain = vr.stream().map(r -> { Document d = new Document((String) r.getOrDefault("content", ""), new LinkedHashMap<>(r)); Object s = r.get("score"); if (s instanceof Number) d.getMetadata().put("score", ((Number) s).doubleValue()); return d; }).collect(Collectors.toList());
        steps.add(new PipelineStep(2, "多路检索", "🔎", now() - t2, vr.isEmpty() ? "empty" : "success", "Dense+Sparse+BM25 → RRF融合 → " + vr.size() + " 条候选", d2));
        if (vr.isEmpty()) return new DebugResult(query, now() - totalStart, steps, List.of(), "", 0);

        long t3 = now(); Map<String, Object> d3 = new LinkedHashMap<>(); d3.put("candidatesBeforeRerank", chain.size()); d3.put("crossEncoderModel", "BGE-Reranker-v2-m3"); d3.put("mmrEnabled", true); d3.put("mmrLambda", 0.7);
        var rr3 = rerankService.rerank(searchQuery, chain); chain = rr3.documents();
        for (int i = 0; i < Math.min(chain.size(), rr3.scores().size()); i++) chain.get(i).getMetadata().put("score", rr3.scores().get(i).score()); d3.put("afterCrossEncoder", chain.size());
        steps.add(new PipelineStep(3, "重排序", "📊", now() - t3, chain.isEmpty() ? "empty" : "success", "Cross-Encoder+MMR → " + d3.get("candidatesBeforeRerank") + "→" + chain.size() + " 条", d3));

        long t4 = now(); Map<String, Object> d4 = new LinkedHashMap<>(); d4.put("threshold", 0.2); d4.put("beforeFilter", chain.size());
        var f4 = chain.stream().filter(d -> { Object s = d.getMetadata().get("score"); return s instanceof Number && ((Number) s).doubleValue() >= 0.2; }).collect(Collectors.toList());
        if (f4.isEmpty() && !chain.isEmpty()) { f4 = List.of(chain.get(0)); d4.put("fallback", "全部低于阈值"); } d4.put("afterFilter", f4.size()); d4.put("removedCount", chain.size() - f4.size()); chain = f4;
        steps.add(new PipelineStep(4, "阈值过滤", "✂️", now() - t4, f4.size() < (int) d4.get("beforeFilter") ? "filtered" : "success", "阈值 0.2 → " + d4.get("beforeFilter") + "→" + f4.size() + " 条", d4));

        long t5 = now(); Map<String, Object> d5 = new LinkedHashMap<>();
        String ctxt = ragSearchService.formatContext(chain); List<Map<String, Object>> traces = ragSearchService.buildTraces(chain);
        d5.put("lostInMiddle", true); d5.put("contentDedupApplied", true); d5.put("uniqueDocuments", traces.stream().map(tr -> (String) tr.get("documentName")).distinct().count()); d5.put("contextLength", ctxt.length());
        steps.add(new PipelineStep(5, "上下文构建", "📝", now() - t5, "success", "Lost-in-Middle+去重 → " + traces.size() + " 段，共 " + ctxt.length() + " 字符", d5));

        return new DebugResult(query, now() - totalStart, steps, traces, ctxt, traces.size());
    }
}
