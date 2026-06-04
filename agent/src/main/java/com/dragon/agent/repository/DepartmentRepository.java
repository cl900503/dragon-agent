package com.dragon.agent.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dragon.agent.entity.DepartmentEntity;

public interface DepartmentRepository extends JpaRepository<DepartmentEntity, Long> {
    Optional<DepartmentEntity> findByName(String name);
}
