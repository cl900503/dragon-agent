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
import io.milvus.v2.service.collection.request.*;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.vector.request.*;
import io.milvus.v2.service.vector.request.data.*;
import io.milvus.v2.service.vector.request.FunctionScore;
import io.milvus.v2.service.vector.request.ranker.WeightedRanker;
import io.milvus.v2.service.vector.response.*;
import jakarta.annotation.PostConstruct;

/**
 * Hybrid Search 服务 — Dense(BGE-M3) + Sparse(BM25 via BGE-M3) + RRF.
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

    @PostConstruct
    void init() {
        String token = milvusUser + ":" + milvusPassword;
        client = new MilvusClientV2(ConnectConfig.builder().uri("http://" + host + ":" + port).token(token).build());
        ensureCollection();
    }

    private void ensureCollection() {
        try {
            boolean existed = client.hasCollection(HasCollectionReq.builder().collectionName(collection).build());

            if (!existed) {
                CreateCollectionReq.CollectionSchema schema = client.createSchema();
                schema.setEnableDynamicField(true);
                schema.addField(AddFieldReq.builder().fieldName("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(true).build());
                schema.addField(AddFieldReq.builder().fieldName("content").dataType(DataType.VarChar).maxLength(8192).enableAnalyzer(true).build());
                schema.addField(AddFieldReq.builder().fieldName("dense_vector").dataType(DataType.FloatVector).dimension(dim).build());
                schema.addField(AddFieldReq.builder().fieldName("sparse_vector").dataType(DataType.SparseFloatVector).build());

                client.createCollection(CreateCollectionReq.builder()
                        .collectionName(collection).collectionSchema(schema).build());

                IndexParam denseIdx = IndexParam.builder().fieldName("dense_vector").indexType(IndexParam.IndexType.HNSW).metricType(IndexParam.MetricType.COSINE).build();
                IndexParam sparseIdx = IndexParam.builder().fieldName("sparse_vector").indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX).metricType(IndexParam.MetricType.IP).build();
                client.createIndex(CreateIndexReq.builder().collectionName(collection).indexParams(List.of(denseIdx, sparseIdx)).build());
                log.info("Hybrid collection [{}] created: dense({}d, COSINE)+sparse(IP)", collection, dim);
            }

            // 每次启动都加载——避免重启后 collection 未加载导致 2004 错误
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
                        // Milvus sparse format: {"tokenId": weight, ...}
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

    /**
     * Hybrid Search——Dense + Sparse 双路检索，WeightedRanker 融合。
     *
     * @param denseVec  BGE-M3 稠密向量（1024 维）
     * @param sparse    BGE-M3 稀疏向量（{indices: [...], values: [...]} 格式），可为 null
     * @param filter    Milvus 过滤表达式
     * @param topK      返回结果数
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> hybridSearch(List<Double> denseVec, Map<String, Object> sparse,
            String filter, int topK) {
        return hybridSearch(denseVec, sparse, filter, topK, null, 0.8f, 0.2f);
    }

    /**
     * 三路 Hybrid Search——Dense + Sparse + BM25 关键词检索，RRF 融合。
     *
     * @param denseVec     BGE-M3 稠密向量（1024 维）
     * @param sparse       BGE-M3 稀疏向量
     * @param filter       Milvus 过滤表达式
     * @param topK         返回结果数
     * @param queryText    原始查询文本（用于 BM25 关键词匹配），可为 null
     * @param denseWeight  稠密检索权重（默认 0.7）
     * @param sparseWeight 稀疏检索权重（默认 0.2），BM25 = 1 - denseWeight - sparseWeight
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> hybridSearch(List<Double> denseVec, Map<String, Object> sparse,
            String filter, int topK, String queryText, float denseWeight, float sparseWeight) {
        try {
            FloatVec dv = new FloatVec(denseVec.stream().map(Double::floatValue).collect(Collectors.toList()));

            // 使用 BGE-M3 真实稀疏向量，不可用时降级为纯 dense
            SparseFloatVec sv = null;
            if (sparse != null && sparse.containsKey("indices") && sparse.containsKey("values")) {
                List<Integer> indices = (List<Integer>) sparse.get("indices");
                List<Double> values = (List<Double>) sparse.get("values");
                SortedMap<Long, Float> sparseMap = new TreeMap<>();
                for (int i = 0; i < indices.size(); i++) {
                    sparseMap.put(indices.get(i).longValue(), values.get(i).floatValue());
                }
                sv = new SparseFloatVec(sparseMap);
            }

            String f = (filter != null && !filter.isBlank()) ? filter : null;

            // === 三路检索 ===

            // 路 1：Dense 检索
            AnnSearchReq denseReq = AnnSearchReq.builder().vectorFieldName("dense_vector").vectors(List.of(dv))
                    .metricType(IndexParam.MetricType.COSINE).limit(topK * 2).params("{\"nprobe\":16}")
                    .filter(f).build();

            // 路 2：Sparse 检索（如果稀疏向量可用）
            List<AnnSearchReq> searchReqs = new ArrayList<>();
            searchReqs.add(denseReq);
            List<Float> weights = new ArrayList<>();
            weights.add(denseWeight);

            if (sv != null) {
                AnnSearchReq sparseReq = AnnSearchReq.builder().vectorFieldName("sparse_vector").vectors(List.of(sv))
                        .metricType(IndexParam.MetricType.IP).limit(topK * 2)
                        .params("{\"drop_ratio_search\":0.2}").filter(f).build();
                searchReqs.add(sparseReq);
                weights.add(sparseWeight);
            }

            // 路 3：BM25 关键词检索（如果提供了查询文本）
            List<Map<String, Object>> bm25Results = List.of();
            if (queryText != null && !queryText.isBlank()) {
                bm25Results = keywordSearch(queryText, f, topK);
            }

            // 如果只有 dense（稀疏不可用），调整权重
            if (sv == null && weights.size() == 1) {
                weights.set(0, 1.0f);
            }

            // === WeightedRanker 融合（Dense + Sparse） ===
            // 统一使用 HybridSearchReq（即使只有一路检索）
            if (searchReqs.size() == 1 && weights.size() == 1) {
                weights.set(0, 1.0f);
            }

            HybridSearchReq req = HybridSearchReq.builder()
                    .collectionName(collection).searchRequests(searchReqs)
                    .functionScore(FunctionScore.builder()
                            .addFunction(WeightedRanker.builder().weights(weights).build()).build())
                    .limit(topK * 2)
                    .outFields(List.of("content","documentId","originalName","chunkIndex","userId","kbId")).build();

            SearchResp resp = client.hybridSearch(req);
            List<Map<String, Object>> vectorResults = new ArrayList<>();
            for (var resultList : resp.getSearchResults()) {
                for (var sr : resultList) {
                    Map<String, Object> item = new LinkedHashMap<>(sr.getEntity());
                    item.put("id", String.valueOf(sr.getId()));
                    item.put("score", sr.getScore());
                    vectorResults.add(item);
                }
            }

            // === RRF 融合向量结果和 BM25 关键词结果 ===
            if (bm25Results.isEmpty()) {
                // 仅向量结果，裁剪到 topK
                return vectorResults.stream().limit(topK).collect(Collectors.toList());
            }

            return reciprocalRankFusion(vectorResults, bm25Results, topK);

        } catch (Exception e) {
            log.warn("Hybrid search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 关键词检索——利用 Milvus 分词器做关键词级匹配。
     *
     * <p>将查询文本拆分为关键词，用 Milvus 表达式 OR 匹配，
     * 按关键词命中数 + 密度打分。</p>
     */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> keywordSearch(String queryText, String filter, int topK) {
        try {
            // 提取关键词：去除常见停用词和标点，保留 2 字以上的词
            String[] words = queryText.replaceAll("[\\p{P}\\s]+", " ").trim().split("\\s+");
            List<String> keywords = new ArrayList<>();
            for (String w : words) {
                if (w.length() >= 2) keywords.add(w);
            }
            if (keywords.isEmpty()) return List.of();

            // 构建 Milvus 过滤表达式：content like "%kw1%" or content like "%kw2%" ...
            StringBuilder expr = new StringBuilder();
            for (int i = 0; i < Math.min(keywords.size(), 5); i++) {
                if (i > 0) expr.append(" or ");
                expr.append("content like \"%").append(escapeExpr(keywords.get(i))).append("%\"");
            }
            if (filter != null && !filter.isBlank()) {
                expr.insert(0, "(").append(") and (").append(filter).append(")");
            }

            var resp = client.query(io.milvus.v2.service.vector.request.QueryReq.builder()
                    .collectionName(collection)
                    .filter(expr.toString())
                    .outputFields(List.of("content","documentId","originalName","chunkIndex","userId","kbId"))
                    .limit(topK * 2)
                    .build());

            var queryResp = resp.getQueryResults();
            List<Map<String, Object>> results = new ArrayList<>();
            for (var row : queryResp) {
                Map<String, Object> item = new LinkedHashMap<>();
                Map<String, Object> entity = row.getEntity();
                if (entity != null) {
                    item.putAll(entity);
                }
                // Milvus Int64 auto-ID → String，避免后续 Long→String 转型报错
                Object rawId = item.get("id");
                if (rawId instanceof Long) {
                    item.put("id", String.valueOf(rawId));
                }
                // 打分：关键词命中密度
                Object contentObj = entity != null ? entity.get("content") : null;
                String content = contentObj instanceof String ? (String) contentObj : "";
                double score = keywordScore(content, keywords);
                item.put("score", score);
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

    /**
     * Reciprocal Rank Fusion——合并向量检索和关键词检索结果。
     *
     * <p>公式：score(d) = Σ 1/(k + rank_i(d))，k=60
     */
    List<Map<String, Object>> reciprocalRankFusion(List<Map<String, Object>> vectorResults,
            List<Map<String, Object>> keywordResults, int topK) {
        final double K = 60.0;
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, Map<String, Object>> allItems = new LinkedHashMap<>();

        // 处理向量结果
        for (int i = 0; i < vectorResults.size(); i++) {
            var item = vectorResults.get(i);
            String id = safeGetId(item, String.valueOf(i));
            rrfScores.merge(id, 1.0 / (K + i + 1), Double::sum);
            allItems.putIfAbsent(id, item);
        }

        // 处理关键词结果
        for (int i = 0; i < keywordResults.size(); i++) {
            var item = keywordResults.get(i);
            String id = safeGetId(item, "kw_" + i);
            rrfScores.merge(id, 1.0 / (K + i + 1), Double::sum);
            allItems.putIfAbsent(id, item);
        }

        // 按 RRF 分数降序排列
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

    /** 类型安全地从 Map 中提取 ID，兼容 Milvus Long 和 String 两种 id 类型 */
    static String safeGetId(Map<String, Object> item, String fallback) {
        Object id = item.get("id");
        if (id == null) return fallback;
        if (id instanceof String s) return s;
        return String.valueOf(id);
    }

    /** 转义 Milvus 表达式中的特殊字符 */
    private static String escapeExpr(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 简单的关键词命中密度打分 */
    private static double keywordScore(String content, List<String> keywords) {
        if (content == null || content.isEmpty() || keywords.isEmpty()) return 0;
        String lower = content.toLowerCase();
        int hits = 0;
        for (String kw : keywords) {
            int idx = 0;
            while ((idx = lower.indexOf(kw.toLowerCase(), idx)) != -1) {
                hits++;
                idx += kw.length();
            }
        }
        // 密度 = hits / (chars/100)，归一到 [0, 1]
        return Math.min(1.0, hits / Math.max(1.0, lower.length() / 100.0));
    }
}
