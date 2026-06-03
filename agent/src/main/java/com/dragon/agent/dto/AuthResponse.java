package com.dragon.agent.dto;

/**
 * 认证接口统一响应体。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
public record AuthResponse(String username, String message) {
}
