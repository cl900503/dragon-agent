package com.dragon.agent.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

/**
 * AI 对话服务——所有 AI 调用的统一入口，封装 Spring AI ChatClient。
 *
 * chat()   —— 同步调用，阻塞等待完整回复后返回。
 * stream() —— 流式调用，返回 Flux<ChatResponse>，由 Controller 层负责
 *            映射为 SSE 事件流。
 *
 * 注意 chat() 是阻塞操作，调用方（ChatController）需自行切到 boundedElastic
 * 线程池，避免阻塞 Netty I/O 线程。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
@Service
public class AiService {

    private final ChatClient chatClient;

    /**
     * 构造器注入 ChatClient.Builder，Spring Boot 自动配置提供。
     * builder.build() 读取 application.yaml 中 spring.ai.deepseek 配置。
     */
    public AiService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * 同步对话，阻塞等待完整回复。
     * 链路：ChatClient.prompt().user(msg).call().content() → HTTP POST 到 DeepSeek API。
     */
    public String chat(String message) {
        return chatClient.prompt()
                .user(message)
                .call()
                .content();
    }

    /**
     * 流式对话，返回 Spring AI 原生的 Flux<ChatResponse>。
     * 每个 ChatResponse 包含一次生成步骤的结果，Controller 层负责从中提取
     * reasoning_content（DeepSeek R1 思考过程）和正文 token 并转为 SSE 事件。
     */
    public Flux<ChatResponse> stream(String message) {
        return chatClient.prompt()
                .user(message)
                .stream()
                .chatResponse();
    }
}
