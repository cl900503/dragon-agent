package com.dragon.agent.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dragon.agent.entity.ConversationEntity;
import com.dragon.agent.entity.MessageEntity;
import com.dragon.agent.entity.ReasoningTrace;
import com.dragon.agent.entity.RetrievalTrace;
import com.dragon.agent.entity.UserEntity;
import com.dragon.agent.repository.ConversationRepository;
import com.dragon.agent.repository.MessageRepository;
import com.dragon.agent.repository.ReasoningTraceRepository;
import com.dragon.agent.repository.RetrievalTraceRepository;
import com.dragon.agent.repository.ToolTraceRepository;
import com.dragon.agent.repository.UserRepository;

/**
 * 会话管理服务——ChatMemory + ReasoningTrace + RetrievalTrace + ToolTrace。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
@Service
public class ConversationService {

    @Autowired
    private ChatMemory chatMemory;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ReasoningTraceRepository reasoningTraceRepository;

    @Autowired
    private RetrievalTraceRepository retrievalTraceRepository;

    @Autowired
    private ToolTraceRepository toolTraceRepository;

    @Autowired
    private UserRepository userRepository;

    public String resolveConversationId(String conversationId, String username) {
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("用户不存在: " + username));
        if (conversationId == null || conversationId.isBlank()) {
            String newId = UUID.randomUUID().toString();
            conversationRepository.save(new ConversationEntity(newId, user.getId(), "新对话"));
            return newId;
        }
        ConversationEntity existing = conversationRepository.findById(conversationId).orElse(null);
        if (existing == null) {
            conversationRepository.save(new ConversationEntity(conversationId, user.getId(), "新对话"));
        } else if (!existing.getUserId().equals(user.getId())) {
            throw new SecurityException("无权访问此会话");
        }
        return conversationId;
    }

    /**
     * 返回含推理链和检索追溯的完整消息历史。
     *
     * <p>使用批量查询替代逐条 N+1 查询，减少数据库往返次数。</p>
     */
    public List<Map<String, Object>> getMessages(String conversationId) {
        List<MessageEntity> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        if (messages.isEmpty()) {
            return List.of();
        }

        List<String> msgIds = messages.stream().map(MessageEntity::getId).toList();
        List<String> assistantMsgIds = new ArrayList<>();
        List<String> userMsgIds = new ArrayList<>();
        for (MessageEntity msg : messages) {
            if ("ASSISTANT".equals(msg.getRole())) {
                assistantMsgIds.add(msg.getId());
            } else if ("USER".equals(msg.getRole())) {
                userMsgIds.add(msg.getId());
            }
        }

        // 批量加载推理链
        Map<String, String> reasoningByMsgId = new LinkedHashMap<>();
        if (!assistantMsgIds.isEmpty()) {
            for (var rt : reasoningTraceRepository.findByMessageIdIn(assistantMsgIds)) {
                reasoningByMsgId.put(rt.getMessageId(), rt.getContent());
            }
        }

        // 批量加载检索追溯
        Map<String, List<RetrievalTrace>> retrievalByMsgId = new LinkedHashMap<>();
        if (!userMsgIds.isEmpty()) {
            for (var rt : retrievalTraceRepository.findByMessageIdIn(userMsgIds)) {
                retrievalByMsgId.computeIfAbsent(rt.getMessageId(), k -> new ArrayList<>()).add(rt);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (MessageEntity msg : messages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", msg.getId());
            item.put("messageType", msg.getRole());
            item.put("text", msg.getContent());

            String reasoning = reasoningByMsgId.get(msg.getId());
            if (reasoning != null) {
                item.put("reasoning", reasoning);
            }

            List<RetrievalTrace> retrievals = retrievalByMsgId.get(msg.getId());
            if (retrievals != null && !retrievals.isEmpty()) {
                List<Map<String, Object>> rtList = new ArrayList<>();
                for (RetrievalTrace rt : retrievals) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("documentName", rt.getDocumentName());
                    r.put("chunkIndex", rt.getChunkIndex());
                    r.put("contentSnippet", rt.getContentSnippet());
                    if (rt.getScore() != null) {
                        r.put("score", rt.getScore());
                    }
                    rtList.add(r);
                }
                item.put("retrievalTraces", rtList);
            }
            result.add(item);
        }
        return result;
    }

    public void saveUserMessage(String id, String conversationId, String content) {
        messageRepository.save(new MessageEntity(id, conversationId, "USER", content));
    }

    public void saveAssistantMessage(String id, String conversationId, String content) {
        messageRepository.save(new MessageEntity(id, conversationId, "ASSISTANT", content));
    }

    public void saveReasoningTrace(String id, String messageId, String conversationId, String content) {
        reasoningTraceRepository.save(new ReasoningTrace(id, messageId, conversationId, content));
    }

    public void saveRetrievalTraces(String messageId, String conversationId, List<Map<String, Object>> traces) {
        for (Map<String, Object> t : traces) {
            String tid = UUID.randomUUID().toString();
            RetrievalTrace rt = new RetrievalTrace();
            rt.setId(tid);
            rt.setMessageId(messageId);
            rt.setConversationId(conversationId);
            rt.setDocumentName((String) t.get("documentName"));
            rt.setChunkIndex((Integer) t.get("chunkIndex"));
            rt.setScore((Double) t.get("score"));
            rt.setContentSnippet((String) t.get("contentSnippet"));
            retrievalTraceRepository.save(rt);
        }
    }

    @Transactional
    public void clearConversation(String conversationId, String username) {
        chatMemory.clear(conversationId);
        retrievalTraceRepository.deleteByConversationId(conversationId);
        reasoningTraceRepository.deleteByConversationId(conversationId);
        toolTraceRepository.deleteByConversationId(conversationId);
        messageRepository.deleteByConversationId(conversationId);
        UserEntity user = userRepository.findByUsername(username).orElse(null);
        if (user != null) {
            conversationRepository.deleteByIdAndUserId(conversationId, user.getId());
        }
    }

    public List<Map<String, String>> listConversations(String username) {
        UserEntity user = userRepository.findByUsername(username).orElse(null);
        if (user == null)
            return List.of();
        List<ConversationEntity> entities = conversationRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        List<Map<String, String>> result = new ArrayList<>();
        for (ConversationEntity entity : entities) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("id", entity.getId());
            item.put("title", getConversationTitle(entity.getId()));
            result.add(item);
        }
        return result;
    }

    public boolean isOwner(String conversationId, String username) {
        UserEntity user = userRepository.findByUsername(username).orElse(null);
        if (user == null)
            return false;
        return conversationRepository.findByIdAndUserId(conversationId, user.getId()).isPresent();
    }

    public void updateConversationTitle(String conversationId) {
        String title = getConversationTitle(conversationId);
        conversationRepository.findById(conversationId).ifPresent(entity -> {
            entity.setTitle(title);
            conversationRepository.save(entity);
        });
    }

    private String getConversationTitle(String conversationId) {
        List<MessageEntity> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        for (MessageEntity msg : messages) {
            if ("USER".equals(msg.getRole())) {
                String text = msg.getContent();
                if (text != null && !text.isBlank()) {
                    return text.length() > 30 ? text.substring(0, 30) + "..." : text;
                }
            }
        }
        return "新对话";
    }
}
