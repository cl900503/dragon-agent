package com.dragon.agent.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dragon.agent.entity.RagFeedback;

/**
 * RAG 检索反馈 JPA 仓库。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
public interface RagFeedbackRepository extends JpaRepository<RagFeedback, Long> {

    Optional<RagFeedback> findByMessageIdAndUserId(String messageId, Long userId);

    boolean existsByMessageIdAndUserId(String messageId, Long userId);

    @Query("SELECT f.rating, COUNT(f) FROM RagFeedback f WHERE f.createdAt BETWEEN :from AND :to GROUP BY f.rating")
    List<Object[]> countByRatingBetween(@Param("from") java.time.Instant from, @Param("to") java.time.Instant to);
}
