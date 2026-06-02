package com.dragon.agent.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dragon.agent.entity.ReasoningTrace;

public interface ReasoningTraceRepository extends JpaRepository<ReasoningTrace, String> {
    Optional<ReasoningTrace> findByMessageId(String messageId);
    void deleteByConversationId(String conversationId);
}
