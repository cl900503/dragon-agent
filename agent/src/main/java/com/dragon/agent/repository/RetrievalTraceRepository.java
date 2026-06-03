package com.dragon.agent.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dragon.agent.entity.RetrievalTrace;

public interface RetrievalTraceRepository extends JpaRepository<RetrievalTrace, String> {
    List<RetrievalTrace> findByMessageId(String messageId);

    void deleteByConversationId(String conversationId);
}
