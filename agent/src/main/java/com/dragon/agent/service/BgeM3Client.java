package com.dragon.agent.service;

import java.util.*;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.*;

/**
 * BGE-M3 本地 Embedding 客户端。
 */
@Service
public class BgeM3Client {

    private static final Logger log = LoggerFactory.getLogger(BgeM3Client.class);
    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.bge-m3.url:http://localhost:8081/embed}")
    private String url;

    @SuppressWarnings("unchecked")
    public Map<String, Object> embed(String text) {
        try {
            Map<String, Object> body = Map.of("inputs", List.of(text), "modes", List.of("dense", "sparse"));
            HttpHeaders h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON);
            var resp = rest.postForEntity(url, new HttpEntity<>(mapper.writeValueAsString(body), h), String.class);
            if (resp.getBody() == null) return null;
            JsonNode data = mapper.readTree(resp.getBody()).get("data").get(0);
            Map<String, Object> r = new LinkedHashMap<>();
            if (data.has("dense")) {
                List<Double> dense = new ArrayList<>();
                for (var v : data.get("dense")) dense.add(v.asDouble());
                r.put("dense", dense);
            }
            if (data.has("sparse")) {
                JsonNode sp = data.get("sparse");
                List<Integer> idx = new ArrayList<>(); List<Double> val = new ArrayList<>();
                for (var v : sp.get("indices")) idx.add(v.asInt());
                for (var v : sp.get("values")) val.add(v.asDouble());
                r.put("sparse", Map.of("indices", idx, "values", val));
            }
            return r;
        } catch (Exception e) { log.warn("BGE-M3 embed failed: {}", e.getMessage()); return null; }
    }
}
