package com.dragon.agent.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;

/**
 * 会话管理服务——对话的生命周期管理。
 *
 * 负责会话 ID 解析、历史消息存取、会话列表维护。
 * 不同会话通过 conversationId 隔离，互不干扰。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
@Service
public class ConversationService {

    private final ChatMemory chatMemory;
    private final ChatMemoryRepository chatMemoryRepository;

    /** 记录每个会话的首次创建时间，用于按时间排序会话列表 */
    private final Map<String, Instant> conversationCreatedAt = new ConcurrentHashMap<>();

    public ConversationService(ChatMemory chatMemory, ChatMemoryRepository chatMemoryRepository) {
        this.chatMemory = chatMemory;
        this.chatMemoryRepository = chatMemoryRepository;
    }

    /**
     * 解析会话 ID——为空时生成新 UUID 并记录创建时间。
     *
     * 此方法是 conversationId 解析的唯一来源，
     * Controller 层应直接传入请求中的原始值，不应自行解析。
     */
    public String resolveConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            String newId = UUID.randomUUID().toString();
            conversationCreatedAt.put(newId, Instant.now());
            return newId;
        }
        conversationCreatedAt.putIfAbsent(conversationId, Instant.now());
        return conversationId;
    }

    /**
     * 获取指定会话的全部历史消息。
     *
     * @param conversationId 会话 ID
     * @return 消息列表，按时间顺序排列
     */
    public List<Message> getMessages(String conversationId) {
        return chatMemory.get(conversationId);
    }

    /**
     * 清除指定会话的全部历史消息。
     *
     * @param conversationId 会话 ID
     */
    public void clearConversation(String conversationId) {
        chatMemory.clear(conversationId);
    }

    /**
     * 列出所有会话，按创建时间倒序排列（最新在前）。
     *
     * 每个会话包含 id 和 title 两个字段。
     * title 取第一条用户消息的前 30 个字符，无用户消息时显示"新对话"。
     *
     * @return 会话摘要列表
     */
    public List<Map<String, String>> listConversations() {
        List<String> ids = chatMemoryRepository.findConversationIds();
        ids.sort(Comparator.comparing(
                id -> conversationCreatedAt.getOrDefault(id, Instant.EPOCH),
                Comparator.reverseOrder()));

        List<Map<String, String>> result = new ArrayList<>();
        for (String id : ids) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("title", getConversationTitle(id));
            result.add(item);
        }
        return result;
    }

    /**
     * 获取会话标题——第一条用户消息的前 30 个字符。
     */
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
