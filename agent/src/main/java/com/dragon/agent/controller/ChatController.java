package com.dragon.agent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dragon.agent.dto.ChatRequest;
import com.dragon.agent.service.AiService;
import com.dragon.agent.service.ConversationService;

import jakarta.validation.Valid;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 同步对话接口——等待 AI 完整回复后一次性返回。
 *
 * 多轮对话通过 conversationId 关联上下文，
 * 响应头 X-Conversation-Id 返回实际使用的会话 ID，前端应保存此值。
 *
 * 会话管理委托给 ConversationService，AI 调用委托给 AiService。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final AiService aiService;
    private final ConversationService conversationService;

    public ChatController(AiService aiService, ConversationService conversationService) {
        this.aiService = aiService;
        this.conversationService = conversationService;
    }

    /**
     * POST /api/chat
     *
     * 请求体示例：
     *   {"message": "你好", "conversationId": "uuid-可选"}
     *
     * 响应示例：
     *   "你好！有什么可以帮助你的？"
     *
     * @param request 包含 message 和可选 conversationId 的请求体
     * @return AI 完整回复文本，附带 X-Conversation-Id 响应头
     */
    @PostMapping("/chat")
    public Mono<ResponseEntity<String>> chat(@Valid @RequestBody ChatRequest request) {
        String cid = conversationService.resolveConversationId(request.conversationId());
        return Mono.fromCallable(() -> aiService.chat(request.message(), cid))
                .subscribeOn(Schedulers.boundedElastic())
                .map(content -> ResponseEntity.ok()
                        .header("X-Conversation-Id", cid)
                        .body(content));
    }
}
