package com.dragon.agent.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * RAG 检索反馈——记录用户对 AI 回复的检索质量评价。
 *
 * @author 陈龙
 * @since 2026-06-04
 */
@Entity
@Table(name = "rag_feedback")
public class RagFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, length = 36)
    private String messageId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(length = 10)
    @Enumerated(EnumType.STRING)
    private Rating rating;

    @Column(length = 500)
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() { if (createdAt == null) createdAt = Instant.now(); }

    public RagFeedback() {}

    public RagFeedback(String messageId, Long userId, Rating rating, String comment) {
        this.messageId = messageId;
        this.userId = userId;
        this.rating = rating;
        this.comment = comment;
    }

    public Long getId() { return id; }
    public String getMessageId() { return messageId; }
    public Long getUserId() { return userId; }
    public Rating getRating() { return rating; }
    public String getComment() { return comment; }
    public Instant getCreatedAt() { return createdAt; }

    public enum Rating { USEFUL, USELESS }
}
