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
            if (client.hasCollection(HasCollectionReq.builder().collectionName(collection).build())) return;

            CreateCollectionReq.CollectionSchema schema = client.createSchema();
            schema.setEnableDynamicField(true);
            schema.addField(AddFieldReq.builder().fieldName("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(true).build());
            schema.addField(AddFieldReq.builder().fieldName("content").dataType(DataType.VarChar).maxLength(8192).enableAnalyzer(true).build());
            schema.addField(AddFieldReq.builder().fieldName("dense_vector").dataType(DataType.FloatVector).dimension(dim).build());
            schema.addField(AddFieldReq.builder().fieldName("sparse_vector").dataType(DataType.SparseFloatVector).build());

            client.createCollection(CreateCollectionReq.builder()
                    .collectionName(collection).collectionSchema(schema).build());

            IndexParam denseIdx = IndexParam.builder().fieldName("dense_vector").indexType(IndexParam.IndexType.HNSW).metricType(IndexParam.MetricType.IP).build();
            IndexParam sparseIdx = IndexParam.builder().fieldName("sparse_vector").indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX).metricType(IndexParam.MetricType.IP).build();
            client.createIndex(CreateIndexReq.builder().collectionName(collection).indexParams(List.of(denseIdx, sparseIdx)).build());
            client.loadCollection(LoadCollectionReq.builder().collectionName(collection).build());
            log.info("Hybrid collection [{}] ready: dense({}d)+sparse", collection, dim);
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

    public List<Map<String, Object>> hybridSearch(List<Double> denseVec, String filter, int topK) {
        try {
            FloatVec dv = new FloatVec(denseVec.stream().map(Double::floatValue).collect(Collectors.toList()));
            SortedMap<Long, Float> sparseMap = new TreeMap<>(); sparseMap.put(0L, 1.0f);
            SparseFloatVec sv = new SparseFloatVec(sparseMap);

            String f = (filter != null && !filter.isBlank()) ? filter : null;
            AnnSearchReq denseReq = AnnSearchReq.builder().vectorFieldName("dense_vector").vectors(List.of(dv))
                    .metricType(IndexParam.MetricType.IP).limit(topK).params("{\"nprobe\":16}")
                    .filter(f).build();
            AnnSearchReq sparseReq = AnnSearchReq.builder().vectorFieldName("sparse_vector").vectors(List.of(sv))
                    .metricType(IndexParam.MetricType.IP).limit(topK).params("{\"drop_ratio_search\":0.2}")
                    .filter(f).build();

            HybridSearchReq req = HybridSearchReq.builder()
                    .collectionName(collection).searchRequests(List.of(denseReq, sparseReq))
                    .functionScore(FunctionScore.builder()
                            .addFunction(WeightedRanker.builder().weights(List.of(0.8f, 0.2f)).build()).build())
                    .limit(topK)
                    .outFields(List.of("content","documentId","originalName","chunkIndex","userId","kbId")).build();

            SearchResp resp = client.hybridSearch(req);
            List<Map<String, Object>> results = new ArrayList<>();
            for (var resultList : resp.getSearchResults()) {
                for (var sr : resultList) {
                    Map<String, Object> item = new LinkedHashMap<>(sr.getEntity());
                    item.put("id", String.valueOf(sr.getId()));
                    item.put("score", sr.getScore());
                    results.add(item);
                }
            }
            return results;
        } catch (Exception e) { log.warn("Hybrid search failed: {}", e.getMessage()); return List.of(); }
    }
}
