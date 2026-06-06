package com.dragon.agent.enums;

/**
 * RAG 检索质量反馈评分枚举。
 *
 * @author 陈龙
 * @since 2026-06-06
 */
public enum RagRating {

    /** 检索结果有用 */
    USEFUL,

    /** 检索结果无用 */
    USELESS;

    public static RagRating fromString(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
