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
public record DocumentResponse(String id, String originalName, Long fileSize, String mimeType,
        String kbId, String kbName, String uploaderName, Long userId,
        DocumentStatus status, Integer chunkCount, String errorMessage, Instant createdAt,
        boolean canDelete) {

    public static DocumentResponse enriched(DocumentEntity entity, String kbName, String uploaderName, boolean canDelete) {
        return new DocumentResponse(entity.getId(), entity.getOriginalName(), entity.getFileSize(),
                entity.getMimeType(), entity.getKbId(), kbName, uploaderName, entity.getUserId(),
                entity.getStatus(), entity.getChunkCount(),
                entity.getErrorMessage(), entity.getCreatedAt(), canDelete);
    }
}
