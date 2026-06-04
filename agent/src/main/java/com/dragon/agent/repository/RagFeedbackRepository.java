package com.dragon.agent.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dragon.agent.entity.RagFeedback;

public interface RagFeedbackRepository extends JpaRepository<RagFeedback, Long> {

    List<RagFeedback> findByMessageId(String messageId);

    boolean existsByMessageIdAndUserId(String messageId, Long userId);

    @Query("SELECT f.rating, COUNT(f) FROM RagFeedback f WHERE f.createdAt BETWEEN :from AND :to GROUP BY f.rating")
    List<Object[]> countByRatingBetween(@Param("from") java.time.Instant from, @Param("to") java.time.Instant to);
}
