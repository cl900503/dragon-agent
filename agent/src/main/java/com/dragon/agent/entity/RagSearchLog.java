package com.dragon.agent.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * RAG 检索日志——记录每次检索的关键指标，用于质量分析。
 *
 * @author 陈龙
 * @since 2026-06-04
 */
@Entity
@Table(name = "rag_search_logs", indexes = {
        @Index(name = "idx_searchlog_user_time", columnList = "user_id,created_at")
})
public class RagSearchLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 2000)
    private String query;

    @Column(name = "kb_ids", length = 500)
    private String kbIds;

    @Column(name = "result_count")
    private Integer resultCount;

    @Column(name = "top_score")
    private Double topScore;

    @Column(name = "avg_score")
    private Double avgScore;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "hit", nullable = false, columnDefinition = "TINYINT(1)")
    private boolean hit;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() { if (createdAt == null) createdAt = Instant.now(); }

    public RagSearchLog() {}

    public RagSearchLog(Long userId, String query, String kbIds, int resultCount,
            Double topScore, Double avgScore, long durationMs, boolean hit) {
        this.userId = userId;
        this.query = query;
        this.kbIds = kbIds;
        this.resultCount = resultCount;
        this.topScore = topScore;
        this.avgScore = avgScore;
        this.durationMs = durationMs;
        this.hit = hit;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getQuery() { return query; }
    public String getKbIds() { return kbIds; }
    public Integer getResultCount() { return resultCount; }
    public Double getTopScore() { return topScore; }
    public Double getAvgScore() { return avgScore; }
    public Long getDurationMs() { return durationMs; }
    public boolean isHit() { return hit; }
    public Instant getCreatedAt() { return createdAt; }
}
