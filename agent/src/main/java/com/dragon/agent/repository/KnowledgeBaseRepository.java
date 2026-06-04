package com.dragon.agent.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dragon.agent.entity.KnowledgeBaseEntity;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, String> {
    Optional<KnowledgeBaseEntity> findByIdAndOwnerId(String id, Long ownerId);
}
