package com.dragon.agent.controller;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dragon.agent.dto.ChatRequest;
import com.dragon.agent.service.AiService;
import com.dragon.agent.service.ConversationService;

import jakarta.validation.Valid;
import reactor.core.publisher.Flux;

/**
 * SSE 流式对话接口——逐 token 推送 AI 回复，实现打字机效果。
 *
 * 三种标准 SSE 事件类型：
 *   event:thinking — 推理思考过程（仅推理模型产生，如 DeepSeek R1）
 *   event:content  — 正文回复内容（所有模型均产生）
 *   event:done     — 流结束信号（固定为末尾事件）
 *
 * 典型事件序列：
 *   推理模型：thinking* → content* → done
 *   普通模型：content* → done
 *
 * 推理内容提取和模型适配逻辑封装在 AiService 中，
 * Controller 不依赖任何模型特有的实现类。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
@RestController
@RequestMapping("/api")
public class StreamController {

    private final AiService aiService;
    private final ConversationService conversationService;

    public StreamController(AiService aiService, ConversationService conversationService) {
        this.aiService = aiService;
        this.conversationService = conversationService;
    }

    /**
     * POST /api/stream
     * Content-Type: text/event-stream
     *
     * 请求体示例：
     *   {"message": "解释相对论", "conversationId": "uuid-可选"}
     *
     * 前端使用 EventSource 或 fetch + ReadableStream 接收事件流。
     * conversationId 为空时服务端自动生成新会话。
     *
     * @param request 包含 message 和可选 conversationId 的请求体
     * @return SSE 事件流
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody ChatRequest request) {
        String cid = conversationService.resolveConversationId(request.conversationId());
        return aiService.stream(request.message(), cid);
    }
}
