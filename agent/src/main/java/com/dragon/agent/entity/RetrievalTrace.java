package com.dragon.agent.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * RAG 检索追溯（RetrievalTrace）——记录每次对话中检索到的知识库文档片段。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
@Entity
@Table(name = "retrieval_traces", indexes = {
    @Index(name = "idx_rt_message", columnList = "message_id"),
    @Index(name = "idx_rt_conversation", columnList = "conversation_id")
})
public class RetrievalTrace {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "message_id", nullable = false, length = 36)
    private String messageId;  // FK → chat_messages.id (USER)

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    @Column(name = "document_name", nullable = false, length = 500)
    private String documentName;

    @Column(name = "chunk_index")
    private Integer chunkIndex;

    @Column(name = "score")
    private Double score;

    @Column(name = "content_snippet", columnDefinition = "TEXT")
    private String contentSnippet;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public RetrievalTrace() {}

    public RetrievalTrace(String id, String messageId, String conversationId,
                          String documentName, Integer chunkIndex, Double score, String contentSnippet) {
        this.id = id;
        this.messageId = messageId;
        this.conversationId = conversationId;
        this.documentName = documentName;
        this.chunkIndex = chunkIndex;
        this.score = score;
        this.contentSnippet = contentSnippet;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getDocumentName() { return documentName; }
    public void setDocumentName(String documentName) { this.documentName = documentName; }
    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public String getContentSnippet() { return contentSnippet; }
    public void setContentSnippet(String contentSnippet) { this.contentSnippet = contentSnippet; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
