package com.dragon.agent.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * AI 对话服务——所有 AI 调用的统一入口。
 *
 * 封装 Spring AI ChatClient，通过 MessageChatMemoryAdvisor 自动管理对话历史。
 * 不同会话通过 conversationId 隔离，互不干扰。
 *
 * 职责边界：
 *   - 对话 ID 解析（唯一来源，Controller 层不应重复此逻辑）
 *   - 推理内容提取（模型特有逻辑封装在 Service 内，不泄漏到 Controller）
 *   - 会话列表排序（按创建时间倒序）
 *
 * @author 陈龙
 * @since 2026-05-31
 */
@Service
public class AiService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ChatMemoryRepository chatMemoryRepository;

    /** 记录每个会话的首次创建时间，用于按时间排序会话列表 */
    private final Map<String, Instant> conversationCreatedAt = new ConcurrentHashMap<>();

    public AiService(ChatClient.Builder builder, ChatMemory chatMemory,
                     ChatMemoryRepository chatMemoryRepository) {
        this.chatMemory = chatMemory;
        this.chatMemoryRepository = chatMemoryRepository;
        this.chatClient = builder
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /**
     * 同步对话——等待 AI 完整回复后一次性返回。
     *
     * @param message        用户消息
     * @param conversationId 会话 ID，为空则自动创建新会话
     * @return AI 完整回复文本
     */
    public String chat(String message, String conversationId) {
        String cid = resolveConversationId(conversationId);
        return chatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))
                .user(message)
                .call()
                .content();
    }

    /**
     * SSE 流式对话——逐 token 推送 AI 回复，实现打字机效果。
     *
     * 返回三种标准 SSE 事件：
     *   event:thinking — 推理过程 token（仅推理模型产生）
     *   event:content  — 正文回复 token
     *   event:done     — 流结束信号
     *
     * @param message        用户消息
     * @param conversationId 会话 ID，为空则自动创建新会话
     * @return SSE 事件流
     */
    public Flux<ServerSentEvent<String>> stream(String message, String conversationId) {
        String cid = resolveConversationId(conversationId);

        ServerSentEvent<String> doneEvent = ServerSentEvent.<String>builder()
                .event("done")
                .data("")
                .build();

        return chatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, cid))
                .user(message)
                .stream()
                .chatResponse()
                .flatMap(response -> {
                    Flux<ServerSentEvent<String>> events = Flux.empty();
                    for (var gen : response.getResults()) {
                        AssistantMessage output = gen.getOutput();

                        String reasoning = extractReasoningContent(output);
                        if (reasoning != null && !reasoning.isEmpty()) {
                            events = events.concatWith(Mono.just(
                                    ServerSentEvent.<String>builder()
                                            .event("thinking")
                                            .data(reasoning)
                                            .build()));
                        }

                        String content = output.getText();
                        if (content != null && !content.isEmpty()) {
                            events = events.concatWith(Mono.just(
                                    ServerSentEvent.<String>builder()
                                            .event("content")
                                            .data(content)
                                            .build()));
                        }
                    }
                    return events;
                })
                .concatWith(Mono.just(doneEvent));
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

    /**
     * 从 AssistantMessage 中提取推理思考内容。
     *
     * 当前支持 DeepSeek R1 系列模型。接入其他推理模型时，
     * 在此处追加对应的 instanceof 分支即可，无需修改 Controller。
     *
     * 此方法封装了模型特有的类型判断，对外暴露为普通的 AssistantMessage，
     * 确保 Controller 层不依赖任何模型特有的实现类。
     */
    private String extractReasoningContent(AssistantMessage output) {
        if (output instanceof org.springframework.ai.deepseek.DeepSeekAssistantMessage deepMsg) {
            return deepMsg.getReasoningContent();
        }
        return null;
    }
}
