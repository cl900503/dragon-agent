package com.dragon.agent.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dragon.agent.entity.DocumentEntity;

/**
 * 文档元数据 JPA 仓库。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
public interface DocumentRepository extends JpaRepository<DocumentEntity, String> {

    /** 按用户和会话查询文档列表（按创建时间倒序） */
    List<DocumentEntity> findByUserIdAndConversationIdOrderByCreatedAtDesc(Long userId, String conversationId);

    /** 按用户查询所有文档（全局知识库场景） */
    List<DocumentEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 按用户查询无会话关联的文档 */
    List<DocumentEntity> findByUserIdAndConversationIdIsNullOrderByCreatedAtDesc(Long userId);

    /** 按文档 ID 和用户 ID 查询（所有权验证） */
    Optional<DocumentEntity> findByIdAndUserId(String id, Long userId);

    /** 按会话 ID 统计文档数量 */
    long countByConversationId(String conversationId);

    /** 删除指定会话下的所有文档 */
    void deleteByConversationId(String conversationId);
}
