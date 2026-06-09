package com.dragon.agent.service.rag;

import java.util.*;
import java.util.stream.Collectors;

import com.google.gson.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.service.collection.request.*;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.vector.request.*;
import io.milvus.v2.service.vector.request.data.*;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.response.*;
import jakarta.annotation.PostConstruct;

/**
 * 三路独立检索 + RRF 融合 —— Dense(BGE-M3) / Sparse(BGE-M3) / BM25(关键词) 各自独立检索，应用层 RRF 融合。
 *
 * <p>不使用 Milvus 内置 WeightedRanker，因为：
 * <ul>
 *   <li>Dense (COSINE)、Sparse (IP)、BM25 (关键词密度) 三者分数尺度不一致</li>
 *   <li>RRF 不依赖分数尺度，只依赖排序位置，更适合异构召回融合</li>
 *   <li>固定权重无法适应不同查询类型（短查询需 BM25 主导，长查询需 Dense 主导）</li>
 * </ul>
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);
    private MilvusClientV2 client;

    @Value("${spring.ai.vectorstore.milvus.client.host:localhost}")
    private String host;
    @Value("${spring.ai.vectorstore.milvus.client.port:19530}")
    private int port;
    @Value("${spring.ai.vectorstore.milvus.collection-name:vector_store}")
    private String collection;
    @Value("${spring.ai.vectorstore.milvus.embedding-dimension:1024}")
    private int dim;
    @Value("${spring.ai.vectorstore.milvus.client.username:root}")
    private String milvusUser;
    @Value("${spring.ai.vectorstore.milvus.client.password:Milvus}")
    private String milvusPassword;

    /** RRF 平滑因子 */
    static final int RRF_K = 60;

    /** 包内可见：供管线调试输出读取实际参数 */
    int getRrfK() { return RRF_K; }
    String getDenseSearchParams() { return "{\"nprobe\":16}"; }

    @PostConstruct
    void init() {
        String token = milvusUser + ":" + milvusPassword;
        client = new MilvusClientV2(ConnectConfig.builder().uri("http://" + host + ":" + port).token(token).build());
        ensureCollection();
    }

    private void ensureCollection() {
        try {
            boolean existed = client.hasCollection(HasCollectionReq.builder().collectionName(collection).build());
            boolean needRecreate = false;
            if (existed) {
                // 检查是否有 bm25_vector 字段，没有则重建（schema 升级）
                try {
                    var desc = client.describeCollection(DescribeCollectionReq.builder().collectionName(collection).build());
                    boolean hasBM25 = desc.getFieldNames().contains("bm25_vector");
                    if (!hasBM25) {
                        log.info("Collection [{}] missing bm25_vector, dropping for recreation...", collection);
                        client.dropCollection(DropCollectionReq.builder().collectionName(collection).build());
                        existed = false;
                    }
                } catch (Exception e) { log.warn("Schema check failed: {}", e.getMessage()); }
            }

            if (!existed) {
                CreateCollectionReq.CollectionSchema schema = client.createSchema();
                schema.setEnableDynamicField(true);
                schema.addField(AddFieldReq.builder().fieldName("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(true).build());
                schema.addField(AddFieldReq.builder().fieldName("content").dataType(DataType.VarChar).maxLength(8192).enableAnalyzer(true).build());
                schema.addField(AddFieldReq.builder().fieldName("dense_vector").dataType(DataType.FloatVector).dimension(dim).build());
                schema.addField(AddFieldReq.builder().fieldName("sparse_vector").dataType(DataType.SparseFloatVector).build());
                // BM25 Function: 从 content 自动生成 bm25_vector
                schema.addField(AddFieldReq.builder().fieldName("bm25_vector").dataType(DataType.SparseFloatVector).build());
                schema.addFunction(io.milvus.v2.service.collection.request.CreateCollectionReq.Function.builder()
                        .functionType(FunctionType.BM25)
                        .name("bm25_func").inputFieldNames(List.of("content")).outputFieldNames(List.of("bm25_vector")).build());
                client.createCollection(CreateCollectionReq.builder().collectionName(collection).collectionSchema(schema).build());
                IndexParam denseIdx = IndexParam.builder().fieldName("dense_vector").indexType(IndexParam.IndexType.HNSW).metricType(IndexParam.MetricType.COSINE).build();
                IndexParam sparseIdx = IndexParam.builder().fieldName("sparse_vector").indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX).metricType(IndexParam.MetricType.IP).build();
                IndexParam bm25Idx = IndexParam.builder().fieldName("bm25_vector").indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX).metricType(IndexParam.MetricType.BM25).build();
                client.createIndex(CreateIndexReq.builder().collectionName(collection).indexParams(List.of(denseIdx, sparseIdx, bm25Idx)).build());
                log.info("Hybrid collection [{}] created: dense({}d, COSINE) + sparse(IP) + BM25(func)", collection, dim);
            }
            client.loadCollection(LoadCollectionReq.builder().collectionName(collection).build());
            log.info("Hybrid collection [{}] loaded into memory", collection);
        } catch (Exception e) { log.error("Collection init failed: {}", e.getMessage()); }
    }

    public void insert(List<Map<String, Object>> rows) {
        try {
            Gson gson = new Gson();
            List<JsonObject> data = rows.stream().map(row -> {
                JsonObject obj = new JsonObject();
                for (var e : row.entrySet()) {
                    String k = e.getKey(); Object v = e.getValue();
                    if (v instanceof List<?> l) {
                        JsonArray arr = new JsonArray();
                        for (Object o : l) arr.add(new JsonPrimitive(((Number) o).floatValue()));
                        obj.add(k, arr);
                    } else if (v instanceof Map<?,?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> sp = (Map<String, Object>) m;
                        List<Integer> idx = (List<Integer>) sp.get("indices");
                        List<Double> val = (List<Double>) sp.get("values");
                        JsonObject sparseObj = new JsonObject();
                        for (int j = 0; j < idx.size(); j++) sparseObj.addProperty(String.valueOf(idx.get(j)), val.get(j));
                        obj.add(k, sparseObj);
                    } else if (v instanceof String s) obj.addProperty(k, s);
                    else if (v instanceof Number n) obj.addProperty(k, n);
                }
                return obj;
            }).collect(Collectors.toList());
            client.insert(InsertReq.builder().collectionName(collection).data(data).build());
        } catch (Exception e) { log.error("Insert failed: {}", e.getMessage()); }
    }

    public void deleteByExpr(String expr) {
        try { client.delete(DeleteReq.builder().collectionName(collection).filter(expr).build()); }
        catch (Exception e) { log.error("Delete failed: {}", e.getMessage()); }
    }

    // ==================== 三路独立检索 + RRF 融合 ====================

    /**
     * 三路检索结果——融合后的候选列表 + 各分路原始结果（供调试展示）。
     */
    public record SearchResult(
            List<Map<String, Object>> fusedResults,
            List<Map<String, Object>> denseResults,
            List<Map<String, Object>> sparseResults,
            List<Map<String, Object>> bm25Results) {}

    /**
     * 三路独立检索：Dense(BGE-M3, COSINE) + Sparse(BGE-M3, IP) + BM25(关键词匹配)。
     * 每路取 topK×2 候选，经 RRF(k=60) 融合后返回 topK 条。
     *
     * @param denseVector BGE-M3 稠密向量（1024 维）
     * @param sparseVector BGE-M3 稀疏向量（{indices, values} 或 null）
     * @param filterExpr   Milvus 过滤表达式（userId/kbId 权限控制）
     * @param topK         最终返回候选数
     * @param queryText    原始查询文本（BM25 关键词检索用，null 则跳过）
     */
    @SuppressWarnings("unchecked")
    public SearchResult search(List<Double> denseVector, Map<String, Object> sparseVector,
            String filterExpr, int topK, String queryText) {
        String f = (filterExpr != null && !filterExpr.isBlank()) ? filterExpr : null;
        int limit = topK * 2;

        FloatVec denseVec = new FloatVec(denseVector.stream().map(Double::floatValue).collect(Collectors.toList()));
        List<Map<String, Object>> denseResults = doSingleVectorSearch("dense_vector", denseVec,
                IndexParam.MetricType.COSINE, f, limit, "{\"nprobe\":16}");

        List<Map<String, Object>> sparseResults = List.of();
        if (sparseVector != null && sparseVector.containsKey("indices") && sparseVector.containsKey("values")) {
            List<Integer> indices = (List<Integer>) sparseVector.get("indices");
            List<Double> values = (List<Double>) sparseVector.get("values");
            SortedMap<Long, Float> sparseMap = new TreeMap<>();
            for (int i = 0; i < indices.size(); i++) sparseMap.put(indices.get(i).longValue(), values.get(i).floatValue());
            sparseResults = doSingleVectorSearch("sparse_vector", new SparseFloatVec(sparseMap),
                    IndexParam.MetricType.IP, f, limit, "{\"drop_ratio_search\":0.2}");
        }

        List<Map<String, Object>> bm25Results = List.of();
        if (queryText != null && !queryText.isBlank()) {
            bm25Results = doBm25Search(queryText, f, limit);
        }

        // RRF 融合
        List<List<Map<String, Object>>> paths = new ArrayList<>();
        paths.add(denseResults);
        if (!sparseResults.isEmpty()) paths.add(sparseResults);
        if (!bm25Results.isEmpty()) paths.add(bm25Results);

        List<Map<String, Object>> fused;
        try {
            fused = paths.size() > 1 ? doReciprocalRankFusion(paths, topK)
                    : paths.get(0).stream().limit(topK).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Hybrid search failed: {}", e.getMessage());
            fused = List.of();
        }

        return new SearchResult(fused, denseResults, sparseResults, bm25Results);
    }

    // ==================== 内部实现 ====================

    /** 单路向量检索 */
    private List<Map<String, Object>> doSingleVectorSearch(String vectorField,
            io.milvus.v2.service.vector.request.data.BaseVector vector,
            IndexParam.MetricType metricType, String filterExpr, int limit, String searchParams) {
        try {
            AnnSearchReq annReq = AnnSearchReq.builder().vectorFieldName(vectorField).vectors(List.of(vector))
                    .metricType(metricType).limit(limit).params(searchParams).filter(filterExpr).build();
            HybridSearchReq req = HybridSearchReq.builder().collectionName(collection)
                    .searchRequests(List.of(annReq)).limit(limit)
                    .outFields(List.of("content", "documentId", "originalName", "chunkIndex", "userId", "kbId")).build();
            SearchResp resp = client.hybridSearch(req);
            List<Map<String, Object>> results = new ArrayList<>();
            for (var resultList : resp.getSearchResults()) {
                for (var sr : resultList) {
                    Map<String, Object> item = new LinkedHashMap<>(sr.getEntity());
                    item.put("id", String.valueOf(sr.getId()));
                    results.add(item);
                }
            }
            return results;
        } catch (Exception e) {
            log.warn("Vector search [{}] failed: {}", vectorField, e.getMessage());
            return List.of();
        }
    }

    // ==================== BM25 关键词检索 ====================

    /** 通过 BM25 Function 检索 bm25_vector，传入文本由 Milvus 内部分词和打分 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> doBm25Search(String queryText, String filter, int limit) {
        try {
            SearchReq req = SearchReq.builder()
                    .collectionName(collection)
                    .annsField("bm25_vector")
                    .data(List.of(new EmbeddedText(queryText)))
                    .filter(filter)
                    .topK(limit)
                    .outputFields(List.of("content", "documentId", "originalName", "chunkIndex", "userId", "kbId"))
                    .build();
            SearchResp resp = client.search(req);
            List<Map<String, Object>> results = new ArrayList<>();
            for (var rl : resp.getSearchResults()) {
                for (var sr : rl) {
                    Map<String, Object> item = new LinkedHashMap<>(sr.getEntity());
                    item.put("id", String.valueOf(sr.getId()));
                    item.put("score", sr.getScore());
                    results.add(item);
                }
            }
            log.debug("BM25 search: {} results for \"{}\"", results.size(), queryText);
            return results;
        } catch (Exception e) {
            log.warn("BM25 search failed, falling back to keyword: {}", e.getMessage());
            return doKeywordSearchFallback(queryText, filter, limit);
        }
    }

    /** BM25 Function 不可用时的关键词兜底 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> doKeywordSearchFallback(String queryText, String filter, int limit) {
        try {
            String[] words = queryText.replaceAll("[\\p{P}\\s]+", " ").trim().split("\\s+");
            List<String> keywords = new ArrayList<>();
            for (String w : words) if (w.length() >= 2) keywords.add(w);
            if (keywords.isEmpty()) return List.of();

            StringBuilder expr = new StringBuilder();
            for (int i = 0; i < Math.min(keywords.size(), 5); i++) {
                if (i > 0) expr.append(" or ");
                expr.append("content like \"%").append(escapeExpr(keywords.get(i))).append("%\"");
            }
            if (filter != null && !filter.isBlank()) expr.insert(0, "(").append(") and (").append(filter).append(")");

            var resp = client.query(io.milvus.v2.service.vector.request.QueryReq.builder()
                    .collectionName(collection).filter(expr.toString())
                    .outputFields(List.of("content","documentId","originalName","chunkIndex","userId","kbId"))
                    .limit(limit).build());

            List<Map<String, Object>> results = new ArrayList<>();
            for (var row : resp.getQueryResults()) {
                Map<String, Object> item = new LinkedHashMap<>();
                Map<String, Object> entity = row.getEntity();
                if (entity != null) item.putAll(entity);
                Object rawId = item.get("id");
                if (rawId instanceof Long) item.put("id", String.valueOf(rawId));
                String content = entity != null && entity.get("content") instanceof String s ? s : "";
                item.put("score", keywordScore(content, keywords));
                results.add(item);
            }
            results.sort((a, b) -> Double.compare(
                    ((Number) b.getOrDefault("score", 0)).doubleValue(),
                    ((Number) a.getOrDefault("score", 0)).doubleValue()));
            return results;
        } catch (Exception e) {
            log.warn("Keyword search failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== RRF 融合 ====================

    /**
     * Reciprocal Rank Fusion —— 多路检索结果融合。
     *
     * <p>公式：score(d) = Σ 1/(K + rank_i(d))，K=60
     * <p>不依赖各路分数的绝对尺度，只依赖排序位置，适合异构召回路径。
     */
    private List<Map<String, Object>> doReciprocalRankFusion(List<List<Map<String, Object>>> paths, int topK) {
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, Map<String, Object>> allItems = new LinkedHashMap<>();

        for (var path : paths) {
            for (int i = 0; i < path.size(); i++) {
                var item = path.get(i);
                String id = safeGetId(item, "p" + i);
                rrfScores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
                allItems.putIfAbsent(id, item);
            }
        }

        log.debug("RRF: {} paths → {} unique → top {}", paths.size(), allItems.size(), topK);
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    var item = new LinkedHashMap<>(allItems.get(e.getKey()));
                    item.put("score", e.getValue());
                    return item;
                })
                .collect(Collectors.toList());
    }

    // ==================== helpers ====================

    static String safeGetId(Map<String, Object> item, String fallback) {
        Object id = item.get("id");
        if (id == null) return fallback;
        if (id instanceof String s) return s;
        return String.valueOf(id);
    }

    private static String escapeExpr(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static double keywordScore(String content, List<String> keywords) {
        if (content == null || content.isEmpty() || keywords.isEmpty()) return 0;
        String lower = content.toLowerCase();
        int hits = 0;
        for (String kw : keywords) {
            int idx = 0;
            while ((idx = lower.indexOf(kw.toLowerCase(), idx)) != -1) { hits++; idx += kw.length(); }
        }
        return Math.min(1.0, hits / Math.max(1.0, lower.length() / 100.0));
    }
}
