package com.dragon.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 聊天请求体，使用 Java record 保证不可变性。
 *
 * conversationId 可选，为空时服务端自动生成新会话。
 * 前端需保存返回的 conversationId 以维持多轮对话上下文。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
public record ChatRequest(
        @NotBlank(message = "消息内容不能为空")
        @Size(max = 10000, message = "消息内容不能超过10000个字符")
        String message,

        String conversationId
) {}
