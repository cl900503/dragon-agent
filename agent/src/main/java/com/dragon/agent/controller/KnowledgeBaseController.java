package com.dragon.agent.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dragon.agent.entity.KnowledgeBaseEntity;
import com.dragon.agent.service.KnowledgeBaseService;
import com.dragon.agent.support.SecurityHelper;

import reactor.core.publisher.Mono;

/**
 * 知识库管理接口——CRUD。
 *
 * 权限模型已简化：访问权由 ownerId + visibility + departmentId 三元组决定，
 * 成员管理（kb_members 表）已移除。
 *
 * @author 陈龙
 * @since 2026-06-03
 */
@RestController
@RequestMapping("/api/kb")
public class KnowledgeBaseController {

    @Autowired
    private KnowledgeBaseService kbService;

    @Autowired
    private SecurityHelper securityHelper;

    /** 创建知识库 */
    @PostMapping
    public Mono<ResponseEntity<Map<String, Object>>> create(@RequestBody Map<String, Object> body) {
        return securityHelper.currentUsername().map(username -> {
            try {
                String name = (String) body.get("name");
                String desc = (String) body.getOrDefault("description", "");
                String visStr = (String) body.getOrDefault("visibility", "PRIVATE");
                KnowledgeBaseEntity.KbVisibility visibility = KnowledgeBaseEntity.KbVisibility.valueOf(visStr);
                Long deptId = body.get("departmentId") != null ? Long.valueOf(body.get("departmentId").toString()) : null;
                Integer chunkSize = body.get("chunkSize") != null ? Integer.valueOf(body.get("chunkSize").toString()) : null;
                Integer chunkOverlap = body.get("chunkOverlap") != null ? Integer.valueOf(body.get("chunkOverlap").toString()) : null;
                var kb = kbService.create(name, desc, visibility, deptId, chunkSize, chunkOverlap, username);
                return ResponseEntity.status(201).body(Map.of(
                        "id", kb.getId(), "name", kb.getName(), "visibility", kb.getVisibility().name()));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
            }
        });
    }

    /** 列出我有权限的知识库 */
    @GetMapping
    public Mono<ResponseEntity<List<Map<String, Object>>>> list() {
        return securityHelper.currentUsername()
                .map(username -> ResponseEntity.ok(kbService.listAccessible(username)));
    }

    /** 删除知识库 */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> delete(@PathVariable String id) {
        return securityHelper.currentUsername().map(username -> {
            try {
                kbService.delete(id, username);
                return ResponseEntity.ok(Map.of("id", id, "deleted", true));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.status(403).body(Map.of("id", id, "deleted", false, "error", e.getMessage()));
            }
        });
    }
}
