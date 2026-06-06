package com.dragon.agent.enums;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * 用户角色枚举——替代字符串比较，提供类型安全的角色判断和权限转换。
 *
 * <p>三种角色：
 * <ul>
 *   <li>ADMIN — 系统管理员，全部权限</li>
 *   <li>DEPT_ADMIN — 部门管理员，管理本部门人员和知识库</li>
 *   <li>USER — 普通用户，仅个人数据和有权限知识库的检索</li>
 * </ul>
 *
 * @author 陈龙
 * @since 2026-06-06
 */
public enum UserRole {

    ADMIN,
    DEPT_ADMIN,
    USER;

    /**
     * 转换为 Spring Security 权限字符串。
     */
    public String getAuthority() {
        return "ROLE_" + name();
    }

    /**
     * 转换为 Spring Security GrantedAuthority。
     */
    public GrantedAuthority toAuthority() {
        return new SimpleGrantedAuthority(getAuthority());
    }

    /**
     * 从字符串解析角色，无法识别时默认返回 USER。
     */
    public static UserRole fromString(String role) {
        if (role == null || role.isBlank()) {
            return USER;
        }
        try {
            return valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return USER;
        }
    }

    /**
     * 是否拥有管理员权限（ADMIN 或 DEPT_ADMIN）。
     */
    public boolean isAdminOrAbove() {
        return this == ADMIN || this == DEPT_ADMIN;
    }

    /**
     * 是否为系统管理员。
     */
    public boolean isAdmin() {
        return this == ADMIN;
    }
}
