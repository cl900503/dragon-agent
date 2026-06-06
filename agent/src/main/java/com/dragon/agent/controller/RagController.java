package com.dragon.agent.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dragon.agent.service.RagFeedbackService;
import com.dragon.agent.support.SecurityHelper;

import reactor.core.publisher.Mono;

/**
 * RAG 检索质量接口——反馈提交和按用户隔离的统计数据。
 *
 * <p>Controller 仅负责路由转发，业务逻辑委托给 {@link RagFeedbackService}。</p>
 *
 * @author 陈龙
 * @since 2026-06-04
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    @Autowired
    private RagFeedbackService ragFeedbackService;

    @Autowired
    private SecurityHelper securityHelper;

    /**
     * 提交检索质量反馈。
     */
    @PostMapping("/feedback")
    public Mono<ResponseEntity<Map<String, Object>>> submitFeedback(@RequestBody Map<String, String> body) {
        return securityHelper.currentUsername().map(username -> {
            try {
                ragFeedbackService.submitFeedback(username,
                        body.get("messageId"), body.get("rating"), body.get("comment"));
                return ResponseEntity.status(201).body(Map.of("status", "ok"));
            } catch (IllegalStateException e) {
                return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            } catch (Exception e) {
                return ResponseEntity.status(401).body(Map.of("error", "未登录"));
            }
        });
    }

    /**
     * 检索质量统计（最近 30 天，仅当前用户）。
     */
    @GetMapping("/stats")
    public Mono<ResponseEntity<Map<String, Object>>> stats() {
        return securityHelper.currentUsername().map(username -> {
            try {
                return ResponseEntity.ok(ragFeedbackService.getStats(username));
            } catch (Exception e) {
                return ResponseEntity.status(401).body(Map.of("error", "未登录"));
            }
        });
    }

    /**
     * 批量查询反馈状态（用于页面加载时恢复已有反馈的选中状态）。
     */
    @GetMapping("/feedback/batch")
    public Mono<ResponseEntity<Map<String, String>>> batchFeedback(@RequestParam(name = "ids") String ids) {
        return securityHelper.currentUsername().map(username -> {
            try {
                return ResponseEntity.ok(ragFeedbackService.batchFeedback(username, ids));
            } catch (Exception e) {
                return ResponseEntity.status(401).body(Map.of());
            }
        });
    }

    /**
     * 最近检索记录（仅当前用户）。
     */
    @GetMapping("/recent")
    public Mono<ResponseEntity<List<Map<String, Object>>>> recent() {
        return securityHelper.currentUsername().map(username -> {
            try {
                return ResponseEntity.ok(ragFeedbackService.getRecent(username));
            } catch (Exception e) {
                return ResponseEntity.status(401).body(List.of());
            }
        });
    }
}
