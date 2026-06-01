package com.dragon.agent.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * AI 对话服务——所有 AI 调用的统一入口。
 *
 * 封装 Spring AI ChatClient，通过 MessageChatMemoryAdvisor 自动管理对话历史。
 * 推理内容提取逻辑封装在私有方法中，不泄漏到 Controller 层。
 *
 * 职责边界：
 *   - AI 对话调用（同步 / 流式）
 *   - 推理内容提取（模型特有逻辑，对外透明）
 *
 * 会话管理（ID 解析、消息存取、列表排序）由 {@link ConversationService} 负责。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
@Service
public class AiService {

    private final ChatClient chatClient;

    public AiService(ChatClient.Builder builder, ChatMemory chatMemory) {
        this.chatClient = builder
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /**
     * 同步对话——等待 AI 完整回复后一次性返回。
     *
     * @param message        用户消息
     * @param conversationId 已解析的会话 ID
     * @return AI 完整回复文本
     */
    public String chat(String message, String conversationId) {
        return chatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
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
     * @param conversationId 已解析的会话 ID
     * @return SSE 事件流
     */
    public Flux<ServerSentEvent<String>> stream(String message, String conversationId) {
        ServerSentEvent<String> doneEvent = ServerSentEvent.<String>builder()
                .event("done")
                .data("")
                .build();

        return chatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .user(message)
                .stream()
                .chatResponse()
                .flatMap(response -> {
                    Flux<ServerSentEvent<String>> events = Flux.empty();
                    for (Generation gen : response.getResults()) {
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
