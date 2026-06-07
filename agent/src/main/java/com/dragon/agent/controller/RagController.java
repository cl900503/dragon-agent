package com.dragon.agent.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dragon.agent.service.RagFeedbackService;
import com.dragon.agent.repository.UserRepository;
import com.dragon.agent.service.rag.RagDebugService;
import com.dragon.agent.support.SecurityHelper;

import java.time.Duration;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * RAG 检索质量接口——反馈提交、管线调试和按用户隔离的统计数据。
 *
 * @author 陈龙
 * @since 2026-06-04
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    @Autowired
    private RagFeedbackService ragFeedbackService;

    @Autowired(required = false)
    private RagDebugService ragDebugService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SecurityHelper securityHelper;

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

    /**
     * RAG 管线调试——逐步执行检索并返回每步中间结果。
     */
    @PostMapping("/debug")
    public Mono<ResponseEntity<Map<String, Object>>> debug(@RequestBody Map<String, String> body) {
        String query = body.getOrDefault("query", "");
        if (query.isBlank())
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "query 不能为空")));

        return securityHelper.currentUsername()
                .flatMap(username -> Mono.fromCallable(() -> {
                    Long userId = userRepository.findByUsername(username)
                            .map(u -> u.getId()).orElse(null);
                    return ragDebugService != null
                            ? ragDebugService.debug(query, userId)
                            : null;
                }).subscribeOn(Schedulers.boundedElastic()))
                .timeout(Duration.ofSeconds(30))
                .<ResponseEntity<Map<String, Object>>>map(result -> {
                    if (result == null) {
                        return ResponseEntity.status(503).body(Map.of("error", "调试服务未就绪"));
                    }
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("query", result.query());
                    response.put("totalMs", result.totalMs());
                    response.put("finalCount", result.finalCount());
                    response.put("finalContext", result.finalContext());
                    response.put("finalTraces", result.finalTraces());
                    List<Map<String, Object>> ss = new ArrayList<>();
                    for (var s : result.steps()) {
                        Map<String, Object> sm = new LinkedHashMap<>();
                        sm.put("step", s.step()); sm.put("name", s.name()); sm.put("icon", s.icon());
                        sm.put("durationMs", s.durationMs()); sm.put("status", s.status());
                        sm.put("summary", s.summary()); sm.put("detail", s.detail() != null ? s.detail() : Map.of());
                        ss.add(sm);
                    }
                    response.put("steps", ss);
                    return ResponseEntity.ok(response);
                })
                .onErrorResume(e -> {
                    log.error("RAG debug failed", e);
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                    return Mono.just(ResponseEntity.status(500).body(err));
                })
                .switchIfEmpty(Mono.fromCallable(() -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("error", "未登录");
                    return ResponseEntity.status(401).body(m);
                }));
    }
}
