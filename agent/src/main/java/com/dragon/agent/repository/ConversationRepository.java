package com.dragon.agent.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dragon.agent.entity.ConversationEntity;

/**
 * 会话 JPA 仓库。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
public interface ConversationRepository extends JpaRepository<ConversationEntity, String> {

    /** 查询指定用户的所有会话，按创建时间倒序 */
    List<ConversationEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 校验会话是否属于指定用户 */
    Optional<ConversationEntity> findByIdAndUserId(String id, Long userId);

    /** 删除指定用户的指定会话 */
    void deleteByIdAndUserId(String id, Long userId);
}
