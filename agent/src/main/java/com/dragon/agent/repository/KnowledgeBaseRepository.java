package com.dragon.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dragon.agent.entity.KnowledgeBaseEntity;

/**
 * 知识库 JPA 仓库。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, String> {
}
