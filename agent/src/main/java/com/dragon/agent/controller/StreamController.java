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
import com.dragon.agent.support.SecurityHelper;

import jakarta.validation.Valid;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * SSE 流式对话接口——逐 token 推送 AI 回复，实现打字机效果。
 *
 * 三种标准 SSE 事件类型：
 *   event:thinking — 推理思考过程（仅推理模型产生）
 *   event:content  — 正文回复内容（所有模型均产生）
 *   event:done     — 流结束信号（固定为末尾事件）
 *
 * @author 陈龙
 * @since 2026-05-31
 */
@RestController
@RequestMapping("/api")
public class StreamController {

    private final AiService aiService;
    private final ConversationService conversationService;
    private final SecurityHelper securityHelper;

    public StreamController(AiService aiService, ConversationService conversationService,
                            SecurityHelper securityHelper) {
        this.aiService = aiService;
        this.conversationService = conversationService;
        this.securityHelper = securityHelper;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody ChatRequest request) {
        return securityHelper.currentUsername()
                .flatMapMany(username -> {
                    String cid = conversationService.resolveConversationId(
                            request.conversationId(), username);
                    return aiService.stream(
                            request.message(), cid, request.enableRag(),
                            request.userMsgId(), request.aiMsgId())
                            .doOnComplete(() -> conversationService.updateConversationTitle(cid));
                });
    }
}
