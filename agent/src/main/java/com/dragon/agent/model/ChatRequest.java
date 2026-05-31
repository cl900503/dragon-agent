package com.dragon.agent.model;

import jakarta.validation.constraints.NotBlank;

/**
 * 聊天请求体，使用 Java record 保证不可变性。
 *
 * @NotBlank 确保 msg 不为 null、空串或纯空白。
 * 校验失败抛出 WebExchangeBindException，由 GlobalExceptionHandler 统一处理。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
public record ChatRequest(
        @NotBlank(message = "消息内容不能为空")
        String msg
) {}
