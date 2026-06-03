package com.dragon.agent.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dragon.agent.service.ConversationService;
import com.dragon.agent.support.SecurityHelper;

import reactor.core.publisher.Mono;

/**
 * 会话管理接口。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private SecurityHelper securityHelper;

    @GetMapping
    public Mono<ResponseEntity<List<Map<String, String>>>> listConversations() {
        return securityHelper.currentUsername()
                .map(username -> ResponseEntity.ok(conversationService.listConversations(username)));
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> getConversation(@PathVariable String id) {
        return securityHelper.currentUsername().flatMap(username -> {
            if (!conversationService.isOwner(id, username)) {
                return Mono.just(ResponseEntity.status(403).body(Map.of("error", "无权访问此会话")));
            }
            List<Map<String, Object>> messages = conversationService.getMessages(id);
            return Mono.just(
                    ResponseEntity.ok(Map.of("conversationId", id, "messages", messages, "count", messages.size())));
        });
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> clearConversation(@PathVariable String id) {
        return securityHelper.currentUsername().flatMap(username -> {
            if (!conversationService.isOwner(id, username)) {
                return Mono.just(ResponseEntity.status(403).body(Map.of("error", "无权操作此会话")));
            }
            conversationService.clearConversation(id, username);
            return Mono
                    .just(ResponseEntity.ok(Map.of("conversationId", id, "cleared", true, "timestamp", Instant.now())));
        });
    }
}
