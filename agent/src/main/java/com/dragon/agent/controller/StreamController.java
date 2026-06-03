package com.dragon.agent.controller;

import org.springframework.beans.factory.annotation.Autowired;
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

/**
 * SSE 流式对话接口。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
@RestController
@RequestMapping("/api")
public class StreamController {

    @Autowired
    private AiService aiService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private SecurityHelper securityHelper;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody ChatRequest request) {
        return securityHelper.currentUsername().flatMapMany(username -> {
            String cid = conversationService.resolveConversationId(request.conversationId(), username);
            return aiService
                    .stream(request.message(), cid, request.enableRag(), request.userMsgId(), request.aiMsgId(),
                            username)
                    .doOnComplete(() -> conversationService.updateConversationTitle(cid));
        });
    }
}
