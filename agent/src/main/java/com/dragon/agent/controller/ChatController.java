package com.dragon.agent.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dragon.agent.dto.ChatRequest;
import com.dragon.agent.service.AiService;
import com.dragon.agent.service.ConversationService;
import com.dragon.agent.support.SecurityHelper;

import jakarta.validation.Valid;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 同步对话接口。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    @Autowired
    private AiService aiService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private SecurityHelper securityHelper;

    @PostMapping("/chat")
    public Mono<ResponseEntity<String>> chat(@Valid @RequestBody ChatRequest request) {
        return securityHelper.currentUsername().flatMap(username -> {
            String cid = conversationService.resolveConversationId(request.conversationId(), username);
            return Mono
                    .fromCallable(() -> aiService.chat(request.message(), cid, request.enableRag(),
                            request.userMsgId(), request.aiMsgId(), username))
                    .subscribeOn(Schedulers.boundedElastic()).map(content -> {
                        conversationService.updateConversationTitle(cid);
                        return ResponseEntity.ok().header("X-Conversation-Id", cid).body(content);
                    });
        });
    }
}
