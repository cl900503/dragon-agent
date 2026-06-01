package com.dragon.agent.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dragon.agent.service.ConversationService;
import com.dragon.agent.support.SecurityHelper;

import org.springframework.ai.chat.messages.Message;

import reactor.core.publisher.Mono;

/**
 * 会话管理接口——列表、详情查询和清除会话历史。
 *
 * 所有操作按当前登录用户隔离，非属主会话返回 403。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final SecurityHelper securityHelper;

    public ConversationController(ConversationService conversationService,
                                  SecurityHelper securityHelper) {
        this.conversationService = conversationService;
        this.securityHelper = securityHelper;
    }

    @GetMapping
    public Mono<ResponseEntity<List<Map<String, String>>>> listConversations() {
        return securityHelper.currentUsername()
                .map(username -> ResponseEntity.ok(
                        conversationService.listConversations(username)));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> getConversation(@PathVariable String id) {
        return securityHelper.currentUsername()
                .flatMap(username -> {
                    if (!conversationService.isOwner(id, username)) {
                        return Mono.just(ResponseEntity.status(403)
                                .body(Map.of("error", "无权访问此会话")));
                    }
                    List<Message> messages = conversationService.getMessages(id);
                    return Mono.just(ResponseEntity.ok(Map.of(
                            "conversationId", id,
                            "messages", messages,
                            "count", messages.size())));
                });
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> clearConversation(@PathVariable String id) {
        return securityHelper.currentUsername()
                .flatMap(username -> {
                    if (!conversationService.isOwner(id, username)) {
                        return Mono.just(ResponseEntity.status(403)
                                .body(Map.of("error", "无权操作此会话")));
                    }
                    conversationService.clearConversation(id, username);
                    return Mono.just(ResponseEntity.ok(Map.of(
                            "conversationId", id,
                            "cleared", true,
                            "timestamp", Instant.now())));
                });
    }
}
