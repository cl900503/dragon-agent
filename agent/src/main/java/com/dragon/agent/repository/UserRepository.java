package com.dragon.agent.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dragon.agent.entity.UserEntity;

/**
 * 用户 JPA 仓库。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByUsername(String username);

    boolean existsByUsername(String username);
}
