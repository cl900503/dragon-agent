package com.dragon.agent.enums;

/**
 * 知识库可见性枚举。
 *
 * @author 陈龙
 * @since 2026-06-06
 */
public enum KbVisibility {

    /** 仅创建者可见 */
    PRIVATE,

    /** 所在部门可见 */
    DEPARTMENT,

    /** 全公司可见 */
    COMPANY;

    public static KbVisibility fromString(String s) {
        if (s == null || s.isBlank()) {
            return PRIVATE;
        }
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PRIVATE;
        }
    }
}
