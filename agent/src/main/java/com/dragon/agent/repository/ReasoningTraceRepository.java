package com.dragon.agent.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dragon.agent.entity.ReasoningTrace;

/**
 * 推理追溯 JPA 仓库。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
public interface ReasoningTraceRepository extends JpaRepository<ReasoningTrace, String> {
    Optional<ReasoningTrace> findByMessageId(String messageId);

    List<ReasoningTrace> findByMessageIdIn(List<String> messageIds);

    void deleteByConversationId(String conversationId);
}
