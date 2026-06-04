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

    /** 按知识库查询文档 */
    List<DocumentEntity> findByKbIdOrderByCreatedAtDesc(String kbId);

    /** 按用户查询所有文档 */
    List<DocumentEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 按文档 ID 和用户 ID 查询（所有权验证） */
    Optional<DocumentEntity> findByIdAndUserId(String id, Long userId);

    /** 统计知识库下的文档数量 */
    long countByKbId(String kbId);
}
