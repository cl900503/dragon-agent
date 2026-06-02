package com.dragon.agent.dto;

import java.time.Instant;

import com.dragon.agent.entity.DocumentEntity;
import com.dragon.agent.entity.DocumentStatus;

/**
 * 文档 API 响应 DTO。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
public record DocumentResponse(
        String id,
        String originalName,
        Long fileSize,
        String mimeType,
        String conversationId,
        DocumentStatus status,
        Integer chunkCount,
        String errorMessage,
        Instant createdAt
) {
    /** 从实体构建响应 */
    public static DocumentResponse from(DocumentEntity entity) {
        return new DocumentResponse(
                entity.getId(),
                entity.getOriginalName(),
                entity.getFileSize(),
                entity.getMimeType(),
                entity.getConversationId(),
                entity.getStatus(),
                entity.getChunkCount(),
                entity.getErrorMessage(),
                entity.getCreatedAt()
        );
    }
}
