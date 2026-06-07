package com.dragon.agent.controller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
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

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    @Autowired private RagFeedbackService ragFeedbackService;
    @Autowired(required = false) private RagDebugService ragDebugService;
    @Autowired private UserRepository userRepository;
    @Autowired private SecurityHelper securityHelper;

    @PostMapping("/feedback")
    public Mono<ResponseEntity<Map<String, Object>>> submitFeedback(@RequestBody Map<String, String> body) {
        return securityHelper.currentUsername().map(username -> {
            try { ragFeedbackService.submitFeedback(username, body.get("messageId"), body.get("rating"), body.get("comment"));
                return ResponseEntity.status(201).body(Map.of("status", "ok")); }
            catch (IllegalStateException e) { return ResponseEntity.status(409).body(Map.of("error", e.getMessage())); }
            catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
            catch (Exception e) { return ResponseEntity.status(401).body(Map.of("error", "未登录")); }
        });
    }

    @GetMapping("/stats")
    public Mono<ResponseEntity<Map<String, Object>>> stats() {
        return securityHelper.currentUsername().map(username -> {
            try { return ResponseEntity.ok(ragFeedbackService.getStats(username)); }
            catch (Exception e) { return ResponseEntity.status(401).body(Map.of("error", "未登录")); }
        });
    }

    @GetMapping("/feedback/batch")
    public Mono<ResponseEntity<Map<String, String>>> batchFeedback(@RequestParam("ids") String ids) {
        return securityHelper.currentUsername().map(username -> {
            try { return ResponseEntity.ok(ragFeedbackService.batchFeedback(username, ids)); }
            catch (Exception e) { return ResponseEntity.status(401).body(Map.of()); }
        });
    }

    @GetMapping("/recent")
    public Mono<ResponseEntity<List<Map<String, Object>>>> recent() {
        return securityHelper.currentUsername().map(username -> {
            try { return ResponseEntity.ok(ragFeedbackService.getRecent(username)); }
            catch (Exception e) { return ResponseEntity.status(401).body(List.of()); }
        });
    }

    @PostMapping("/debug")
    public Mono<ResponseEntity<Map<String, Object>>> debug(@RequestBody Map<String, String> body) {
        String query = body.getOrDefault("query", "");
        if (query.isBlank()) return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "query 不能为空")));
        return securityHelper.currentUsername().flatMap(username -> Mono.fromCallable(() -> {
            Long userId = userRepository.findByUsername(username).map(u -> u.getId()).orElse(null);
            return ragDebugService != null ? ragDebugService.debug(query, userId) : null;
        }).subscribeOn(Schedulers.boundedElastic())).timeout(Duration.ofSeconds(30))
        .<ResponseEntity<Map<String, Object>>>map(result -> {
            if (result == null) return ResponseEntity.status(503).body(Map.of("error", "调试服务未就绪"));
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("query", result.query()); resp.put("totalMs", result.totalMs());
            resp.put("finalCount", result.finalCount()); resp.put("finalContext", result.finalContext()); resp.put("finalTraces", result.finalTraces());
            List<Map<String, Object>> ss = new ArrayList<>();
            for (var s : result.steps()) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("step", s.step()); sm.put("name", s.name()); sm.put("icon", s.icon()); sm.put("durationMs", s.durationMs());
                sm.put("status", s.status()); sm.put("summary", s.summary()); sm.put("detail", s.detail() != null ? s.detail() : Map.of());
                ss.add(sm);
            }
            resp.put("steps", ss);
            return ResponseEntity.ok(resp);
        }).onErrorResume(e -> { log.error("RAG debug failed", e);
            return Mono.just(ResponseEntity.status(500).body(Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()))); })
        .switchIfEmpty(Mono.just(ResponseEntity.status(401).body(Map.of("error", "未登录"))));
    }

    // === 轮询式逐步调试（每步完成即时更新，前端轮询渲染） ===
    private final Map<String, Map<String, Object>> debugSessions = new java.util.concurrent.ConcurrentHashMap<>();

    @PostMapping("/debug/start")
    public Mono<ResponseEntity<Map<String, Object>>> debugStart(@RequestBody Map<String, String> body) {
        String query = body.getOrDefault("query", "");
        if (query.isBlank() || ragDebugService == null)
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "query 不能为空")));
        return securityHelper.currentUsername().map(username -> {
            Long userId = userRepository.findByUsername(username).map(u -> u.getId()).orElse(null);
            String sid = java.util.UUID.randomUUID().toString();
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("steps", new ArrayList<Map<String, Object>>()); state.put("done", false);
            debugSessions.put(sid, state);
            Schedulers.boundedElastic().schedule(() -> {
                @SuppressWarnings("unchecked") var slist = (List<Map<String, Object>>) state.get("steps");
                try { ragDebugService.debugStepByStep(query, userId,
                    s -> { Map<String,Object> sm=new LinkedHashMap<>(); sm.put("step",s.step()); sm.put("name",s.name()); sm.put("icon",s.icon()); sm.put("durationMs",s.durationMs()); sm.put("status",s.status()); sm.put("summary",s.summary()); sm.put("detail",s.detail()); slist.add(sm); },
                    r -> { state.put("finalTraces",r.finalTraces()); state.put("finalContext",r.finalContext()); state.put("finalCount",r.finalCount()); state.put("totalMs",r.totalMs()); state.put("done",true); });
                } catch (Exception e) { state.put("error",e.getMessage()); state.put("done",true); }
            });
            return ResponseEntity.ok(Map.of("sessionId", sid));
        });
    }

    @GetMapping("/debug/poll")
    public Mono<ResponseEntity<Map<String, Object>>> debugPoll(@RequestParam("sid") String sid) {
        Map<String, Object> state = debugSessions.get(sid);
        if (state == null) return Mono.just(ResponseEntity.notFound().build());
        if (Boolean.TRUE.equals(state.get("done"))) debugSessions.remove(sid);
        return Mono.just(ResponseEntity.ok(state));
    }
}
