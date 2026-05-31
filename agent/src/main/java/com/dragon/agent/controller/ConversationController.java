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

/**
 * 会话管理接口——列表、详情查询和清除会话历史。
 *
 * 所有操作通过 ConversationService 委托给 Spring AI ChatMemory，
 * 不直接操作底层存储。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /**
     * GET /api/conversations
     *
     * 返回所有会话的 ID 和标题列表，按创建时间倒序排列。
     *
     * 响应示例：
     *   [{"id": "uuid-1", "title": "解释相对论"}, {"id": "uuid-2", "title": "你好"}]
     *
     * @return 会话摘要列表
     */
    @GetMapping
    public ResponseEntity<List<Map<String, String>>> listConversations() {
        return ResponseEntity.ok(conversationService.listConversations());
    }

    /**
     * GET /api/conversations/{id}
     *
     * 获取指定会话的完整消息历史和元信息。
     *
     * 响应示例：
     *   {
     *     "conversationId": "uuid-1",
     *     "messages": [{"messageType": "USER", "text": "你好"}, ...],
     *     "count": 2
     *   }
     *
     * @param id 会话 ID
     * @return 会话详情，包含消息列表和数量
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getConversation(@PathVariable String id) {
        var messages = conversationService.getMessages(id);
        return ResponseEntity.ok(Map.of(
                "conversationId", id,
                "messages", messages,
                "count", messages.size()));
    }

    /**
     * DELETE /api/conversations/{id}
     *
     * 清除指定会话的全部历史消息，会话 ID 本身不会被删除。
     *
     * 响应示例：
     *   {"conversationId": "uuid-1", "cleared": true, "timestamp": "2026-05-31T12:00:00Z"}
     *
     * @param id 会话 ID
     * @return 清除确认
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> clearConversation(@PathVariable String id) {
        conversationService.clearConversation(id);
        return ResponseEntity.ok(Map.of(
                "conversationId", id,
                "cleared", true,
                "timestamp", Instant.now()));
    }
}
