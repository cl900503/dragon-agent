package com.dragon.agent.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dragon.agent.entity.DepartmentEntity;
import com.dragon.agent.entity.UserEntity;
import com.dragon.agent.repository.DepartmentRepository;
import com.dragon.agent.repository.UserRepository;

/**
 * 管理员服务——部门管理、人员管理的业务逻辑。
 *
 * <p>从 AdminController 中抽取，遵循 Controller → Service → Repository 分层原则。
 *
 * @author 陈龙
 * @since 2026-06-04
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    // ==================== 工具方法 ====================

    /** 加载用户 */
    public UserEntity loadUser(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
    }

    /** 要求 ADMIN 角色 */
    public UserEntity requireAdmin(String username) {
        UserEntity u = loadUser(username);
        if (!"ADMIN".equals(u.getRole())) {
            throw new SecurityException("需要系统管理员权限");
        }
        return u;
    }

    /** 要求 ADMIN 或 DEPT_ADMIN */
    public UserEntity requireStaffManager(String username) {
        UserEntity u = loadUser(username);
        if (!"ADMIN".equals(u.getRole()) && !"DEPT_ADMIN".equals(u.getRole())) {
            throw new SecurityException("需要管理员权限");
        }
        return u;
    }

    // ==================== 部门管理 ====================

    /** 列出当前用户可见的部门。ADMIN 看全部，其他仅看本部门。 */
    public List<Map<String, Object>> listDepartments(String username) {
        UserEntity user = loadUser(username);
        List<Map<String, Object>> list = new ArrayList<>();
        for (DepartmentEntity d : departmentRepository.findAll()) {
            if ("ADMIN".equals(user.getRole()) || Objects.equals(d.getId(), user.getDepartmentId())) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", d.getId());
                item.put("name", d.getName());
                item.put("parentId", d.getParentId());
                item.put("path", d.getPath());
                item.put("userCount", userRepository.countByDepartmentId(d.getId()));
                list.add(item);
            }
        }
        return list;
    }

    /** 创建部门——仅 ADMIN */
    @Transactional
    public Map<String, Object> createDepartment(String name, String username) {
        requireAdmin(username);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("部门名称不能为空");
        }
        DepartmentEntity dept = new DepartmentEntity(name.trim(), null, null);
        departmentRepository.save(dept);
        log.info("Department [{}] created by {}", name, username);
        return Map.of("id", dept.getId(), "name", dept.getName());
    }

    /** 重命名部门——仅 ADMIN */
    @Transactional
    public Map<String, Object> renameDepartment(Long id, String newName, String username) {
        requireAdmin(username);
        DepartmentEntity dept = departmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("部门不存在"));
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("部门名称不能为空");
        }
        dept.setName(newName.trim());
        departmentRepository.save(dept);
        log.info("Department [{}] renamed to [{}] by {}", id, newName, username);
        return Map.of("id", dept.getId(), "name", dept.getName());
    }

    /** 删除部门——仅 ADMIN，部门下有人员时拒绝 */
    @Transactional
    public Map<String, Object> deleteDepartment(Long id, String username) {
        requireAdmin(username);
        DepartmentEntity dept = departmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("部门不存在"));
        long userCount = userRepository.countByDepartmentId(id);
        if (userCount > 0) {
            throw new IllegalArgumentException("该部门下有 " + userCount + " 名人员，请先转移或删除人员后再操作");
        }
        departmentRepository.deleteById(id);
        log.info("Department [{}] deleted by {}", dept.getName(), username);
        return Map.of("deleted", true);
    }

    // ==================== 人员管理 ====================

    /** 列出人员。ADMIN 看全部，其他人仅看本部门。 */
    public List<Map<String, Object>> listUsers(String username) {
        UserEntity current = loadUser(username);
        List<Map<String, Object>> list = new ArrayList<>();
        for (UserEntity u : userRepository.findAll()) {
            if (!"ADMIN".equals(current.getRole())) {
                if (!Objects.equals(current.getDepartmentId(), u.getDepartmentId())) continue;
            }
            list.add(toUserMap(u));
        }
        return list;
    }

    /** 创建人员 */
    @Transactional
    public Map<String, Object> createUser(String username, String password, String displayName,
            String email, String role, Long departmentId, String creatorUsername) {
        UserEntity current = requireStaffManager(creatorUsername);
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("用户名和密码不能为空");
        }

        String finalRole;
        Long finalDeptId;

        if ("DEPT_ADMIN".equals(current.getRole())) {
            finalRole = "USER";
            finalDeptId = current.getDepartmentId();
        } else {
            finalRole = role != null ? role : "USER";
            if ("ADMIN".equals(finalRole) && !"ADMIN".equals(current.getRole())) {
                throw new SecurityException("无权创建系统管理员");
            }
            if (departmentId == null) {
                throw new IllegalArgumentException("创建人员时必须指定部门");
            }
            finalDeptId = departmentId;
        }

        userService.register(username, password, displayName != null ? displayName : username,
                email, finalRole, finalDeptId);
        log.info("User [{}] created by {} (role={}, dept={})", username, creatorUsername, finalRole, finalDeptId);
        return Map.of("username", username, "status", "created");
    }

    /** 删除人员 */
    @Transactional
    public Map<String, Object> deleteUser(Long targetId, String username) {
        UserEntity current = requireStaffManager(username);
        UserEntity target = userRepository.findById(targetId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        // 保护最后一个系统管理员
        if ("ADMIN".equals(target.getRole())) {
            long adminCount = userRepository.findAll().stream()
                    .filter(u -> "ADMIN".equals(u.getRole())).count();
            if (adminCount <= 1) {
                throw new IllegalArgumentException("不能删除最后一个系统管理员");
            }
        }

        // DEPT_ADMIN 只能删除本部门普通用户
        if ("DEPT_ADMIN".equals(current.getRole())) {
            if (!"USER".equals(target.getRole())) {
                throw new SecurityException("部门管理员只能删除普通用户");
            }
            if (!Objects.equals(current.getDepartmentId(), target.getDepartmentId())) {
                throw new SecurityException("无权删除其他部门的人员");
            }
        }

        userRepository.deleteById(targetId);
        log.info("User [{}] deleted by {}", target.getUsername(), username);
        return Map.of("deleted", true);
    }

    /** 修改角色 */
    @Transactional
    public Map<String, Object> setUserRole(Long targetId, String newRole, Long departmentId, String username) {
        UserEntity current = requireStaffManager(username);
        UserEntity target = userRepository.findById(targetId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        if (newRole == null || newRole.isBlank()) {
            throw new IllegalArgumentException("角色不能为空");
        }
        // 不可修改其他 ADMIN
        if ("ADMIN".equals(target.getRole()) && !username.equals(target.getUsername())) {
            throw new SecurityException("不允许修改其他系统管理员的角色");
        }
        // DEPT_ADMIN 限制
        if ("DEPT_ADMIN".equals(current.getRole())) {
            if (!Objects.equals(current.getDepartmentId(), target.getDepartmentId())) {
                throw new SecurityException("无权管理其他部门的人员");
            }
            if ("ADMIN".equals(newRole)) {
                throw new SecurityException("部门管理员不能设置系统管理员角色");
            }
        }
        // 部门归属——仅 ADMIN 可改
        if (departmentId != null && "ADMIN".equals(current.getRole())) {
            target.setDepartmentId(departmentId);
        }
        // 部门管理员必须有部门
        if ("DEPT_ADMIN".equals(newRole) && target.getDepartmentId() == null) {
            throw new IllegalArgumentException("部门管理员必须归属一个部门");
        }

        target.setRole(newRole);
        userRepository.save(target);
        log.info("User [{}] role updated to {} by {}", target.getUsername(), newRole, username);
        return Map.of("id", target.getId(), "role", target.getRole(), "departmentId", target.getDepartmentId());
    }

    /** 编辑个人信息 */
    @Transactional
    public Map<String, Object> editProfile(Long targetId, String displayName, String email,
            String newUsername, String currentUsername) {
        UserEntity current = loadUser(currentUsername);
        UserEntity target = userRepository.findById(targetId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));

        boolean isSelf = current.getId().equals(target.getId());
        boolean isAdmin = "ADMIN".equals(current.getRole());
        boolean isDeptAdmin = "DEPT_ADMIN".equals(current.getRole())
                && Objects.equals(current.getDepartmentId(), target.getDepartmentId());

        if (!isSelf && !isAdmin && !isDeptAdmin) {
            throw new SecurityException("无权编辑该用户的信息");
        }

        if (displayName != null && !displayName.isBlank()) {
            target.setDisplayName(displayName.trim());
        }
        if (email != null) {
            target.setEmail(email.trim());
        }
        if ((isAdmin || isDeptAdmin) && newUsername != null && !newUsername.isBlank()
                && !newUsername.equals(target.getUsername())) {
            if (userRepository.existsByUsername(newUsername)) {
                throw new IllegalArgumentException("用户名已存在");
            }
            target.setUsername(newUsername.trim());
        }

        userRepository.save(target);
        return Map.of("id", target.getId(), "username", target.getUsername(),
                "displayName", target.getDisplayName(), "email", target.getEmail());
    }

    // ==================== 内部方法 ====================

    private Map<String, Object> toUserMap(UserEntity u) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", u.getId());
        item.put("username", u.getUsername());
        item.put("displayName", u.getDisplayName());
        item.put("email", u.getEmail());
        item.put("role", u.getRole());
        item.put("departmentId", u.getDepartmentId());
        item.put("status", u.getStatus());
        return item;
    }
}
