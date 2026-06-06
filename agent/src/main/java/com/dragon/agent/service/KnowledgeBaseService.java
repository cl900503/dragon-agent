package com.dragon.agent.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dragon.agent.entity.KnowledgeBaseEntity;
import com.dragon.agent.entity.KnowledgeBaseEntity.KbVisibility;
import com.dragon.agent.entity.UserEntity;
import com.dragon.agent.repository.KnowledgeBaseRepository;
import com.dragon.agent.repository.UserRepository;

/**
 * 知识库管理服务——创建、删除、权限校验。
 *
 * 权限模型（无 kb_members 表，完全由三元组决定）：
 * <ul>
 *   <li>ADMIN —— 全局管理，可操作所有 KB 和文档</li>
 *   <li>DEPT_ADMIN —— 可管理部门内所有 KB 和文档（等同于部门内所有 KB 的 OWNER）</li>
 *   <li>USER —— 只能管理自己创建的 KB 和自己上传的文档</li>
 * </ul>
 *
 * 可见性规则：
 * <ul>
 *   <li>PRIVATE —— 仅 owner 可见</li>
 *   <li>DEPARTMENT —— owner 同部门的人可见</li>
 *   <li>COMPANY —— 所有登录用户可见</li>
 * </ul>
 *
 * @author 陈龙
 * @since 2026-06-03
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    @Autowired
    private KnowledgeBaseRepository kbRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.dragon.agent.repository.DocumentRepository documentRepository;

    @Autowired
    private com.dragon.agent.repository.DepartmentRepository departmentRepository;

    // ==================== 创建 / 删除 ====================

    /**
     * 创建知识库。
     * ADMIN 可创建任意可见性，DEPT_ADMIN 可创建 PRIVATE/DEPARTMENT，USER 仅可创建 PRIVATE。
     */
    @Transactional
    public KnowledgeBaseEntity create(String name, String description, KbVisibility visibility, Long departmentId,
            Integer chunkSize, Integer chunkOverlap, String username) {
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("用户不存在"));

        // DEPT_ADMIN 不允许创建全公司可见
        if ("DEPT_ADMIN".equals(user.getRole()) && visibility == KbVisibility.COMPANY) {
            throw new IllegalArgumentException("部门管理员只能创建部门可见或私有的知识库");
        }
        // 普通 USER 只能创建私有知识库
        if ("USER".equals(user.getRole()) && visibility != KbVisibility.PRIVATE) {
            throw new IllegalArgumentException("普通用户只能创建私有知识库");
        }
        // 部门知识库必须指定 departmentId
        if (visibility == KbVisibility.DEPARTMENT) {
            if ("ADMIN".equals(user.getRole())) {
                // ADMIN 可以指定任意部门，未指定则警告
                if (departmentId == null) {
                    throw new IllegalArgumentException("创建部门知识库时必须指定部门");
                }
            } else {
                // 其他人强制用自己部门
                departmentId = user.getDepartmentId();
                if (departmentId == null) {
                    throw new IllegalArgumentException("您不属于任何部门，无法创建部门知识库");
                }
            }
        }

        String kbId = UUID.randomUUID().toString();
        KnowledgeBaseEntity kb = new KnowledgeBaseEntity(kbId, name, user.getId(), visibility);
        kb.setDescription(description);
        if (visibility == KbVisibility.DEPARTMENT) {
            kb.setDepartmentId(departmentId);
        }
        if (chunkSize != null && chunkSize > 0) kb.setChunkSize(chunkSize);
        if (chunkOverlap != null && chunkOverlap >= 0) kb.setChunkOverlap(chunkOverlap);
        kbRepository.save(kb);
        log.info("Knowledge base [{}] created by {} (visibility={}, deptId={})", name, username, visibility, kb.getDepartmentId());
        return kb;
    }

    /** 删除知识库。ADMIN 可删任意，DEPT_ADMIN 可删同部门的，USER 只能删自己的。 */
    @Transactional
    public void delete(String kbId, String username) {
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("用户不存在"));
        KnowledgeBaseEntity kb = kbRepository.findById(kbId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在"));

        if (!canManage(kb, user)) {
            throw new IllegalArgumentException("无权删除此知识库");
        }
        long docCount = documentRepository.countByKbId(kbId);
        if (docCount > 0) {
            throw new IllegalArgumentException("知识库下还有 " + docCount + " 个文档，请先清空文档后再删除");
        }

        kbRepository.delete(kb);
        log.info("Knowledge base [{}] deleted by {}", kb.getName(), username);
    }

    // ==================== 列表 ====================

    /** 列出用户有权限查看的知识库 */
    public List<Map<String, Object>> listAccessible(String username) {
        UserEntity user = userRepository.findByUsername(username).orElse(null);
        if (user == null) return List.of();

        List<KnowledgeBaseEntity> all = kbRepository.findAll();
        // 批量加载 owner 名称
        var ownerIds = all.stream().map(KnowledgeBaseEntity::getOwnerId).collect(java.util.stream.Collectors.toSet());
        Map<Long, String> ownerNames = userRepository.findAllById(ownerIds).stream()
                .collect(java.util.stream.Collectors.toMap(u -> u.getId(), u -> u.getUsername()));
        // 批量加载部门名称
        var deptIds = all.stream().map(KnowledgeBaseEntity::getDepartmentId)
                .filter(id -> id != null).collect(java.util.stream.Collectors.toSet());
        Map<Long, String> deptNames = deptIds.isEmpty() ? Map.of()
                : departmentRepository.findAllById(deptIds).stream()
                        .collect(java.util.stream.Collectors.toMap(d -> d.getId(), d -> d.getName()));

        List<Map<String, Object>> result = new ArrayList<>();
        for (KnowledgeBaseEntity kb : all) {
            if (canAccess(kb, user)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", kb.getId());
                item.put("name", kb.getName());
                item.put("description", kb.getDescription());
                item.put("visibility", kb.getVisibility().name());
                item.put("ownerId", kb.getOwnerId());
                item.put("ownerName", ownerNames.getOrDefault(kb.getOwnerId(), ""));
                item.put("departmentId", kb.getDepartmentId());
                if (kb.getDepartmentId() != null) {
                    item.put("departmentName", deptNames.get(kb.getDepartmentId()));
                }
                item.put("createdAt", kb.getCreatedAt().toString());
                item.put("docCount", documentRepository.countByKbId(kb.getId()));
                item.put("canUpload", canWrite(kb.getId(), username));
                result.add(item);
            }
        }
        return result;
    }

    /** 查用户有权限的知识库 ID 列表（用于 RAG 过滤） */
    public List<String> getAccessibleKbIds(Long userId) {
        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null) return List.of();

        return kbRepository.findAll().stream()
                .filter(kb -> canAccess(kb, user))
                .map(KnowledgeBaseEntity::getId)
                .toList();
    }

    // ==================== 权限判断 ====================

    /** 能否查看知识库 */
    public boolean canAccess(KnowledgeBaseEntity kb, UserEntity user) {
        // PRIVATE：仅 owner 本人（ADMIN 也不可见）
        if (kb.getVisibility() == KbVisibility.PRIVATE) {
            return kb.getOwnerId().equals(user.getId());
        }
        // 非私有 KB：ADMIN 全部可见
        if ("ADMIN".equals(user.getRole())) return true;
        // COMPANY：所有登录用户可见
        if (kb.getVisibility() == KbVisibility.COMPANY) return true;
        // DEPARTMENT：同部门成员可见（创建时冻结的部门 ID）
        if (kb.getVisibility() == KbVisibility.DEPARTMENT
                && kb.getDepartmentId() != null
                && Objects.equals(user.getDepartmentId(), kb.getDepartmentId())) return true;
        return false;
    }

    /** 能否查看知识库（按 username 查 kbId） */
    public boolean canAccessKb(String kbId, String username) {
        UserEntity user = userRepository.findByUsername(username).orElse(null);
        KnowledgeBaseEntity kb = kbRepository.findById(kbId).orElse(null);
        if (user == null || kb == null) return false;
        return canAccess(kb, user);
    }

    /** 能否管理知识库（删除 KB、删除任意文档） */
    public boolean canManage(KnowledgeBaseEntity kb, UserEntity user) {
        if ("ADMIN".equals(user.getRole())) return true;
        // 普通用户只能管理自己的私有 KB
        if ("USER".equals(user.getRole())
                && kb.getVisibility() == KbVisibility.PRIVATE
                && kb.getOwnerId().equals(user.getId())) return true;
        // 部门管理员可以管理部门内的 KB
        if ("DEPT_ADMIN".equals(user.getRole())
                && kb.getDepartmentId() != null
                && Objects.equals(user.getDepartmentId(), kb.getDepartmentId())) return true;
        return false;
    }

    /** 能否上传文档到知识库 */
    public boolean canWrite(String kbId, String username) {
        UserEntity user = userRepository.findByUsername(username).orElse(null);
        KnowledgeBaseEntity kb = kbRepository.findById(kbId).orElse(null);
        if (user == null || kb == null) return false;
        // ADMIN 可上传到任意 KB
        if ("ADMIN".equals(user.getRole())) return true;
        // 普通用户只能上传到自己的私有 KB
        if ("USER".equals(user.getRole())
                && kb.getVisibility() == KbVisibility.PRIVATE
                && kb.getOwnerId().equals(user.getId())) return true;
        // 部门管理员可上传到私有 KB（自己的）和本部门 KB，不可上传到全公司 KB
        if ("DEPT_ADMIN".equals(user.getRole())) {
            if (kb.getVisibility() == KbVisibility.COMPANY) return false;
            if (kb.getVisibility() == KbVisibility.PRIVATE && kb.getOwnerId().equals(user.getId())) return true;
            if (kb.getVisibility() == KbVisibility.DEPARTMENT
                    && kb.getDepartmentId() != null
                    && Objects.equals(user.getDepartmentId(), kb.getDepartmentId())) return true;
        }
        return false;
    }

    /** 查询用户对 KB 的 owner 身份（用于判断能否删任意文档） */
    public boolean isOwnerOrEquivalent(String kbId, String username) {
        UserEntity user = userRepository.findByUsername(username).orElse(null);
        KnowledgeBaseEntity kb = kbRepository.findById(kbId).orElse(null);
        if (user == null || kb == null) return false;
        return canManage(kb, user);
    }

    // ==================== 内部工具 ====================

    /** 按 ID 和 owner 身份查 KB（保留兼容，内部删除逻辑使用） */
    public KnowledgeBaseEntity findOwnedOrManaged(String kbId, String username) {
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("用户不存在"));
        KnowledgeBaseEntity kb = kbRepository.findById(kbId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在"));
        if (!canManage(kb, user)) {
            throw new IllegalArgumentException("无权操作此知识库");
        }
        return kb;
    }
}
