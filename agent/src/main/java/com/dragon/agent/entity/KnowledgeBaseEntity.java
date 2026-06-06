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

import com.dragon.agent.enums.KbVisibility;

/**
 * 知识库实体——按部门或业务域组织的文档集合。
 *
 * <p>department 在创建时冻结——owner 后续调岗不影响 KB 的部门归属。</p>
 *
 * @author 陈龙
 * @since 2026-06-03
 */
@Entity
@Table(name = "knowledge_bases", indexes = {
        @Index(name = "idx_kb_visibility", columnList = "visibility"),
        @Index(name = "idx_kb_dept", columnList = "department_id"),
        @Index(name = "idx_kb_owner", columnList = "owner_id")
})
public class KnowledgeBaseEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "department_id")
    private Long departmentId;

    @Column(length = 20)
    @Enumerated(EnumType.STRING)
    private KbVisibility visibility;

    @Column(name = "chunk_size")
    private Integer chunkSize;

    @Column(name = "chunk_overlap")
    private Integer chunkOverlap;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public KnowledgeBaseEntity() {}

    public KnowledgeBaseEntity(String id, String name, Long ownerId, KbVisibility visibility) {
        this.id = id;
        this.name = name;
        this.ownerId = ownerId;
        this.visibility = visibility;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public Long getDepartmentId() { return departmentId; }
    public void setDepartmentId(Long departmentId) { this.departmentId = departmentId; }
    public KbVisibility getVisibility() { return visibility; }
    public void setVisibility(KbVisibility visibility) { this.visibility = visibility; }
    public Integer getChunkSize() { return chunkSize; }
    public void setChunkSize(Integer chunkSize) { this.chunkSize = chunkSize; }
    public Integer getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(Integer chunkOverlap) { this.chunkOverlap = chunkOverlap; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
