package com.dragon.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注册/创建用户请求。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空") @Size(min = 2, max = 50, message = "用户名长度需在2-50字符之间") String username,

        @NotBlank(message = "密码不能为空") @Size(min = 4, max = 100, message = "密码长度需在4-100字符之间") String password,

        String displayName,
        String email,
        String role,
        Long departmentId) {
}
