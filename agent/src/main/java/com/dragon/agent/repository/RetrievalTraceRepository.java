package com.dragon.agent.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dragon.agent.entity.RetrievalTrace;

/**
 * 检索追溯 JPA 仓库。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
public interface RetrievalTraceRepository extends JpaRepository<RetrievalTrace, String> {
    List<RetrievalTrace> findByMessageId(String messageId);

    List<RetrievalTrace> findByMessageIdIn(List<String> messageIds);

    void deleteByConversationId(String conversationId);
}
