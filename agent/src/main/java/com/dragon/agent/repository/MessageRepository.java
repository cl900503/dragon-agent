package com.dragon.agent.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dragon.agent.entity.MessageEntity;

public interface MessageRepository extends JpaRepository<MessageEntity, String> {
    List<MessageEntity> findByConversationIdOrderByCreatedAtAsc(String conversationId);
    void deleteByConversationId(String conversationId);
}
