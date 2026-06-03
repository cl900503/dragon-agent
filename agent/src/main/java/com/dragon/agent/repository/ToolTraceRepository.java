package com.dragon.agent.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dragon.agent.entity.ToolTrace;

public interface ToolTraceRepository extends JpaRepository<ToolTrace, String> {
    List<ToolTrace> findByMessageId(String messageId);

    void deleteByConversationId(String conversationId);
}
