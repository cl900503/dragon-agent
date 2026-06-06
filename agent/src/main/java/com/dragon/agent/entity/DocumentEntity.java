package com.dragon.agent.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 上传文档元数据——记录 MinIO 存储路径、处理状态和分块信息。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
@Entity
@Table(name = "documents", indexes = {
        @Index(name = "idx_doc_user_id", columnList = "user_id"),
        @Index(name = "idx_doc_status", columnList = "status"),
        @Index(name = "idx_doc_kb_id", columnList = "kb_id"),
        @Index(name = "idx_doc_user_status", columnList = "user_id,status")
})
public class DocumentEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "kb_id", length = 36)
    private String kbId;

    @Column(name = "original_name", nullable = false, length = 500)
    private String originalName;

    @Column(name = "stored_path", nullable = false, length = 1000)
    private String storedPath;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "mime_type", length = 200)
    private String mimeType;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(length = 50)
    @Enumerated(EnumType.STRING)
    private DocumentStatus status;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public DocumentEntity() {}

    public DocumentEntity(String id, Long userId, String originalName, String storedPath,
            Long fileSize, String mimeType) {
        this.id = id;
        this.userId = userId;
        this.originalName = originalName;
        this.storedPath = storedPath;
        this.fileSize = fileSize;
        this.mimeType = mimeType;
        this.status = DocumentStatus.UPLOADING;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getStoredPath() { return storedPath; }
    public void setStoredPath(String storedPath) { this.storedPath = storedPath; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
    public DocumentStatus getStatus() { return status; }
    public void setStatus(DocumentStatus status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
