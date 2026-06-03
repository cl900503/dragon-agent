package com.dragon.agent.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * 推理思考追溯（ReasoningTrace）——记录 DeepSeek R1 等推理模型的思考过程。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
@Entity
@Table(name = "reasoning_traces")
public class ReasoningTrace {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "message_id", nullable = false, length = 36)
    private String messageId; // FK → chat_messages.id (ASSISTANT)

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null)
            createdAt = Instant.now();
    }

    public ReasoningTrace() {}

    public ReasoningTrace(String id, String messageId, String conversationId, String content) {
        this.id = id;
        this.messageId = messageId;
        this.conversationId = conversationId;
        this.content = content;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
