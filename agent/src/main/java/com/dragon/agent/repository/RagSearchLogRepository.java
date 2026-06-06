package com.dragon.agent.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dragon.agent.entity.RagSearchLog;

/**
 * RAG 检索日志仓库——按用户隔离查询。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
public interface RagSearchLogRepository extends JpaRepository<RagSearchLog, Long> {

    @Query("SELECT COUNT(r), AVG(r.topScore), AVG(r.avgScore), AVG(r.durationMs), "
            + "SUM(CASE WHEN r.hit = false THEN 1 ELSE 0 END) "
            + "FROM RagSearchLog r WHERE r.userId = :userId AND r.createdAt BETWEEN :from AND :to")
    List<Object[]> statsByUserBetween(@Param("userId") Long userId,
            @Param("from") Instant from, @Param("to") Instant to);

    List<RagSearchLog> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);
}
