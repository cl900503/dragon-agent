package com.dragon.agent.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dragon.agent.entity.ConversationEntity;
import com.dragon.agent.entity.UserEntity;
import com.dragon.agent.repository.ConversationRepository;
import com.dragon.agent.repository.UserRepository;

/**
 * 会话管理服务——会话元数据持久化到 MySQL，消息内容委托 ChatMemory（JdbcChatMemory）。
 *
 * 每个会话绑定到创建它的用户，通过 conversations 表的 user_id 字段实现隔离。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
@Service
public class ConversationService {

    private final ChatMemory chatMemory;
    private final ConversationRepository conversationRepository;
    private final UserRepository userRepository;

    public ConversationService(ChatMemory chatMemory,
                               ConversationRepository conversationRepository,
                               UserRepository userRepository) {
        this.chatMemory = chatMemory;
        this.conversationRepository = conversationRepository;
        this.userRepository = userRepository;
    }

    /**
     * 解析会话 ID——为空时生成新 UUID 并保存到 MySQL。
     *
     * 已有会话则校验归属，非属主抛出 SecurityException。
     */
    public String resolveConversationId(String conversationId, String username) {
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("用户不存在: " + username));

        if (conversationId == null || conversationId.isBlank()) {
            String newId = UUID.randomUUID().toString();
            ConversationEntity entity = new ConversationEntity(newId, user.getId(), "新对话");
            conversationRepository.save(entity);
            return newId;
        }

        // 已有会话：校验归属，不存在则创建归属记录（兼容历史数据）
        ConversationEntity existing = conversationRepository.findById(conversationId).orElse(null);
        if (existing == null) {
            ConversationEntity entity = new ConversationEntity(conversationId, user.getId(), "新对话");
            conversationRepository.save(entity);
        } else if (!existing.getUserId().equals(user.getId())) {
            throw new SecurityException("无权访问此会话");
        }
        return conversationId;
    }

    /**
     * 获取指定会话的全部历史消息。
     */
    public List<Message> getMessages(String conversationId) {
        return chatMemory.get(conversationId);
    }

    /**
     * 删除指定会话——清除 ChatMemory 历史消息并删除 MySQL 中的会话记录。
     */
    @Transactional
    public void clearConversation(String conversationId, String username) {
        chatMemory.clear(conversationId);
        UserEntity user = userRepository.findByUsername(username).orElse(null);
        if (user != null) {
            conversationRepository.deleteByIdAndUserId(conversationId, user.getId());
        }
    }

    /**
     * 列出指定用户的所有会话，按创建时间倒序排列。
     */
    public List<Map<String, String>> listConversations(String username) {
        UserEntity user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return List.of();
        }
        List<ConversationEntity> entities =
                conversationRepository.findByUserIdOrderByCreatedAtDesc(user.getId());

        List<Map<String, String>> result = new ArrayList<>();
        for (ConversationEntity entity : entities) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("id", entity.getId());
            item.put("title", getConversationTitle(entity.getId()));
            result.add(item);
        }
        return result;
    }

    /**
     * 校验会话是否属于指定用户。
     */
    public boolean isOwner(String conversationId, String username) {
        UserEntity user = userRepository.findByUsername(username).orElse(null);
        if (user == null) return false;
        return conversationRepository.findByIdAndUserId(conversationId, user.getId()).isPresent();
    }

    /**
     * 更新会话标题——取第一条用户消息的前 30 个字符。
     */
    public void updateConversationTitle(String conversationId) {
        String title = getConversationTitle(conversationId);
        conversationRepository.findById(conversationId).ifPresent(entity -> {
            entity.setTitle(title);
            conversationRepository.save(entity);
        });
    }

    private String getConversationTitle(String conversationId) {
        List<Message> messages = chatMemory.get(conversationId);
        if (messages == null) {
            return "空会话";
        }
        for (Message msg : messages) {
            if (msg.getMessageType() == MessageType.USER) {
                String text = msg.getText();
                if (text != null && !text.isBlank()) {
                    return text.length() > 30 ? text.substring(0, 30) + "..." : text;
                }
            }
        }
        return "新对话";
    }
}
