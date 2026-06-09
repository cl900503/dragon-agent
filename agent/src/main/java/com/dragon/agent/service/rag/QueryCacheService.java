package com.dragon.agent.service.rag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 检索缓存服务——对 Embedding 结果和检索结果进行 LRU 缓存。
 *
 * <p>两级缓存：
 * <ol>
 *   <li><b>Embedding 缓存</b>：相同/相似文本的 BGE-M3 向量结果，避免重复调用 Embedding 服务</li>
 *   <li><b>检索结果缓存</b>：相同查询的完整检索结果，适用于高频重复查询</li>
 * </ol>
 *
 * <p>缓存策略：
 * <ul>
 *   <li>嵌入缓存：精确匹配 key=md5(query)，TTL=30min，maxSize=1000</li>
 *   <li>检索缓存：精确匹配 key=md5(query+userId)，TTL=5min，maxSize=500</li>
 *   <li>后台线程定期清理过期条目，避免内存泄漏</li>
 * </ul>
 *
 * @author 陈龙
 * @since 2026-06-07
 */
@Service
public class QueryCacheService {

    private static final Logger log = LoggerFactory.getLogger(QueryCacheService.class);

    /** 嵌入缓存最大条目数 */
    @Value("${app.cache.embedding.max-size:1000}")
    private int embedMaxSize;

    /** 嵌入缓存 TTL（分钟） */
    @Value("${app.cache.embedding.ttl-minutes:30}")
    private int embedTtlMinutes;

    /** 检索缓存最大条目数 */
    @Value("${app.cache.search.max-size:500}")
    private int searchMaxSize;

    /** 检索缓存 TTL（分钟） */
    @Value("${app.cache.search.ttl-minutes:5}")
    private int searchTtlMinutes;

    @Value("${app.cache.rewrite.ttl-minutes:10}")
    private int rewriteTtlMinutes;

    @Value("${app.cache.rewrite.max-size:200}")
    private int rewriteMaxSize;

    // key → CacheEntry (LRU + TTL)
    private final ConcurrentHashMap<String, CacheEntry<Object>> embedCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry<Object>> searchCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry<Object>> rewriteCache = new ConcurrentHashMap<>();

    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cache-cleaner");
        t.setDaemon(true);
        return t;
    });

    public QueryCacheService() {
        // 每 5 分钟清理过期条目
        cleaner.scheduleAtFixedRate(this::evictExpired, 5, 5, TimeUnit.MINUTES);
    }

    // ==================== Embedding 缓存 ====================

    /**
     * 获取缓存的 Embedding 结果。
     *
     * @param text 查询文本
     * @return 缓存的向量 Map，未命中返回 null
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getEmbedding(String text) {
        String key = md5(text);
        CacheEntry<Object> entry = embedCache.get(key);
        if (entry != null && !entry.isExpired()) {
            log.debug("Embedding cache hit for \"{}\"", truncate(text, 40));
            return (Map<String, Object>) entry.value;
        }
        return null;
    }

    /**
     * 缓存 Embedding 结果。
     *
     * @param text   查询文本
     * @param result BGE-M3 返回的嵌入向量
     */
    public void putEmbedding(String text, Map<String, Object> result) {
        String key = md5(text);
        if (embedCache.size() >= embedMaxSize) {
            evictLru(embedCache);
        }
        embedCache.put(key, new CacheEntry<>(result, embedTtlMinutes));
    }

    // ==================== 检索结果缓存 ====================

    /**
     * 获取缓存的检索结果。
     *
     * @param query  查询文本
     * @param userId 用户 ID（保证数据隔离）
     * @return 缓存的检索结果，未命中返回 null
     */
    @SuppressWarnings("unchecked")
    public RagSearchService.RagResult getSearchResult(String query, Long userId) {
        String key = md5(query + "::" + userId);
        CacheEntry<Object> entry = searchCache.get(key);
        if (entry != null && !entry.isExpired()) {
            log.debug("Search cache hit for \"{}\" (user={})", truncate(query, 40), userId);
            return (RagSearchService.RagResult) entry.value;
        }
        return null;
    }

    /**
     * 缓存检索结果。
     *
     * @param query  查询文本
     * @param userId 用户 ID
     * @param result 检索结果
     */
    public void putSearchResult(String query, Long userId, RagSearchService.RagResult result) {
        String key = md5(query + "::" + userId);
        if (searchCache.size() >= searchMaxSize) {
            evictLru(searchCache);
        }
        searchCache.put(key, new CacheEntry<>(result, searchTtlMinutes));
    }

    // ==================== 改写结果缓存 ====================

    @SuppressWarnings("unchecked")
    public List<String> getRewriteResult(String query) {
        String key = md5("rw:" + query);
        CacheEntry<Object> entry = rewriteCache.get(key);
        if (entry != null && !entry.isExpired()) {
            log.debug("Rewrite cache hit for \"{}\"", truncate(query, 40));
            return (List<String>) entry.value;
        }
        return null;
    }

    public void putRewriteResult(String query, List<String> variants) {
        String key = md5("rw:" + query);
        if (rewriteCache.size() >= rewriteMaxSize) {
            evictLru(rewriteCache);
        }
        rewriteCache.put(key, new CacheEntry<>(variants, rewriteTtlMinutes));
    }

    // ==================== 缓存维护 ====================

    /**
     * 清除所有缓存（用于文档更新后的缓存失效）。
     */
    public void invalidateAll() {
        int embedSize = embedCache.size(), searchSize = searchCache.size(), rewriteSize = rewriteCache.size();
        embedCache.clear(); searchCache.clear(); rewriteCache.clear();
        log.info("Cache invalidated: {} embeddings, {} searches, {} rewrites", embedSize, searchSize, rewriteSize);
    }

    /**
     * 获取缓存统计。
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("embeddingCacheSize", embedCache.size());
        stats.put("searchCacheSize", searchCache.size());
        stats.put("embeddingCacheMax", embedMaxSize);
        stats.put("searchCacheMax", searchMaxSize);
        return stats;
    }

    // ==================== 内部方法 ====================

    /** 定期清理过期条目 */
    private void evictExpired() {
        evictExpired(embedCache, "embedding");
        evictExpired(searchCache, "search");
        evictExpired(rewriteCache, "rewrite");
    }

    private void evictExpired(ConcurrentHashMap<String, CacheEntry<Object>> cache, String name) {
        int before = cache.size();
        cache.entrySet().removeIf(e -> e.getValue().isExpired());
        int removed = before - cache.size();
        if (removed > 0) {
            log.debug("Cache eviction [{}]: removed {} expired entries", name, removed);
        }
    }

    /** LRU：缓存满时移除最早的条目 */
    private void evictLru(ConcurrentHashMap<String, CacheEntry<Object>> cache) {
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (var e : cache.entrySet()) {
            if (e.getValue().createdAt < oldestTime) {
                oldestTime = e.getValue().createdAt;
                oldestKey = e.getKey();
            }
        }
        if (oldestKey != null) {
            cache.remove(oldestKey);
        }
    }

    /** 简单 MD5（避免引入额外依赖） */
    static String md5(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /** 带 TTL 的缓存条目 */
    private static class CacheEntry<T> {
        final T value;
        final long createdAt;
        final long ttlMillis;

        CacheEntry(T value, int ttlMinutes) {
            this.value = value;
            this.createdAt = System.currentTimeMillis();
            this.ttlMillis = TimeUnit.MINUTES.toMillis(ttlMinutes);
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > ttlMillis;
        }
    }
}
