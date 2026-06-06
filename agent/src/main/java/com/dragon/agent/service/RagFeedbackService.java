package com.dragon.agent.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.dragon.agent.entity.RagFeedback;
import com.dragon.agent.entity.UserEntity;
import com.dragon.agent.repository.RagFeedbackRepository;
import com.dragon.agent.repository.RagSearchLogRepository;
import com.dragon.agent.repository.UserRepository;

/**
 * RAG 检索反馈服务——反馈提交、批量查询和按用户隔离的统计数据。
 *
 * <p>从 RagController 提取业务逻辑，Controller 仅负责路由和参数接收。</p>
 *
 * @author 陈龙
 * @since 2026-06-06
 */
@Service
public class RagFeedbackService {

    @Autowired
    private RagFeedbackRepository feedbackRepository;

    @Autowired
    private RagSearchLogRepository searchLogRepository;

    @Autowired
    private UserRepository userRepository;

    /**
     * 提交检索反馈。同一用户对同一消息只能反馈一次。
     *
     * @param username  当前用户名
     * @param messageId 消息 ID
     * @param ratingStr 评分（USEFUL / USELESS）
     * @param comment   可选备注
     * @throws IllegalStateException     用户不存在
     * @throws IllegalArgumentException  messageId 或 rating 为空
     */
    public void submitFeedback(String username, String messageId, String ratingStr, String comment) {
        UserEntity user = requireUser(username);
        if (messageId == null || ratingStr == null) {
            throw new IllegalArgumentException("messageId 和 rating 不能为空");
        }
        if (feedbackRepository.existsByMessageIdAndUserId(messageId, user.getId())) {
            throw new IllegalStateException("已反馈过");
        }
        RagFeedback.Rating rating = RagFeedback.Rating.valueOf(ratingStr);
        feedbackRepository.save(new RagFeedback(messageId, user.getId(), rating, comment));
    }

    /**
     * 批量查询反馈状态——页面加载时恢复已有反馈。
     *
     * @param username 当前用户名
     * @param ids      逗号分隔的消息 ID 列表
     * @return messageId -> rating 的映射（未反馈为 null）
     */
    public Map<String, String> batchFeedback(String username, String ids) {
        UserEntity user = requireUser(username);
        String[] idArray = ids.split(",");
        Map<String, String> result = new LinkedHashMap<>();
        for (String msgId : idArray) {
            String trimmed = msgId.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            var existing = feedbackRepository.findByMessageIdAndUserId(trimmed, user.getId());
            result.put(trimmed, existing.map(f -> f.getRating().name()).orElse(null));
        }
        return result;
    }

    /**
     * 检索质量统计（最近 30 天，仅当前用户）。
     */
    public Map<String, Object> getStats(String username) {
        UserEntity user = requireUser(username);
        Instant now = Instant.now();
        Instant thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS);

        var feedbackStats = feedbackRepository.countByRatingBetween(thirtyDaysAgo, now);
        long total = 0, useful = 0;
        for (Object[] row : feedbackStats) {
            long count = (Long) row[1];
            total += count;
            if (RagFeedback.Rating.USEFUL.equals(row[0])) {
                useful += count;
            }
        }

        var searchStats = searchLogRepository.statsByUserBetween(user.getId(), thirtyDaysAgo, now);
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

        return result;
    }

    /**
     * 最近检索记录（仅当前用户，最多 20 条）。
     */
    public List<Map<String, Object>> getRecent(String username) {
        UserEntity user = requireUser(username);
        var logs = searchLogRepository.findTop20ByUserIdOrderByCreatedAtDesc(user.getId());
        var list = new ArrayList<Map<String, Object>>();
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
        return list;
    }

    private UserEntity requireUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("用户不存在: " + username));
    }
}
