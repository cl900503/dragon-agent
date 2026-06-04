package com.dragon.agent.controller;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dragon.agent.entity.RagFeedback;
import com.dragon.agent.repository.RagFeedbackRepository;
import com.dragon.agent.repository.RagSearchLogRepository;
import com.dragon.agent.repository.UserRepository;
import com.dragon.agent.support.SecurityHelper;

import reactor.core.publisher.Mono;

/**
 * RAG 检索质量接口——反馈与统计。
 *
 * @author 陈龙
 * @since 2026-06-04
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    @Autowired
    private RagFeedbackRepository feedbackRepository;

    @Autowired
    private RagSearchLogRepository searchLogRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SecurityHelper securityHelper;

    /** 提交检索反馈 */
    @PostMapping("/feedback")
    public Mono<ResponseEntity<Map<String, Object>>> submitFeedback(@RequestBody Map<String, String> body) {
        return securityHelper.currentUsername().map(username -> {
            var user = userRepository.findByUsername(username).orElse(null);
            if (user == null) return ResponseEntity.status(401).body(Map.of("error", "未登录"));

            String messageId = body.get("messageId");
            String ratingStr = body.get("rating");
            String comment = body.get("comment");

            if (messageId == null || ratingStr == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "messageId 和 rating 不能为空"));
            }
            if (feedbackRepository.existsByMessageIdAndUserId(messageId, user.getId())) {
                return ResponseEntity.status(409).body(Map.of("error", "已反馈过"));
            }

            RagFeedback.Rating rating = RagFeedback.Rating.valueOf(ratingStr);
            feedbackRepository.save(new RagFeedback(messageId, user.getId(), rating, comment));
            return ResponseEntity.status(201).body(Map.of("status", "ok"));
        });
    }

    /** 检索质量统计（最近 30 天） */
    @GetMapping("/stats")
    public Mono<ResponseEntity<Map<String, Object>>> stats() {
        return securityHelper.currentUsername().map(username -> {
            Instant now = Instant.now();
            Instant thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS);

            var feedbackStats = feedbackRepository.countByRatingBetween(thirtyDaysAgo, now);
            long total = 0, useful = 0;
            for (Object[] row : feedbackStats) {
                long count = (Long) row[1];
                total += count;
                if ("USEFUL".equals(row[0])) useful += count;
            }

            var searchStats = searchLogRepository.statsBetween(thirtyDaysAgo, now);
            Map<String, Object> result = new LinkedHashMap<>();
            if (!searchStats.isEmpty() && searchStats.get(0)[0] != null) {
                Object[] row = searchStats.get(0);
                result.put("totalSearches", row[0]);
                result.put("avgTopScore", row[1]);
                result.put("avgScore", row[2]);
                result.put("avgDurationMs", row[3]);
                result.put("missCount", row[4]);
            } else {
                result.put("totalSearches", 0);
            }
            result.put("feedbackTotal", total);
            result.put("feedbackUseful", useful);
            result.put("feedbackRate", total > 0 ? String.format("%.1f%%", 100.0 * useful / total) : "N/A");

            return ResponseEntity.ok(result);
        });
    }

    /** 最近检索记录 */
    @GetMapping("/recent")
    public Mono<ResponseEntity<java.util.List<Map<String, Object>>>> recent() {
        return securityHelper.currentUsername().map(username -> {
            var logs = searchLogRepository.findTop20ByOrderByCreatedAtDesc();
            var list = new java.util.ArrayList<Map<String, Object>>();
            for (var log : logs) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", log.getId());
                item.put("query", log.getQuery());
                item.put("hit", log.isHit());
                item.put("resultCount", log.getResultCount());
                item.put("topScore", log.getTopScore());
                item.put("durationMs", log.getDurationMs());
                item.put("createdAt", log.getCreatedAt().toString());
                list.add(item);
            }
            return ResponseEntity.ok(list);
        });
    }
}
