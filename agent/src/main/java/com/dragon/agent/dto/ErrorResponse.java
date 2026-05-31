package com.dragon.agent.dto;

import java.time.Instant;

/**
 * 统一错误响应体，所有接口异常都返回这个结构，前端只需处理一种格式。
 *
 * 示例：
 * {
 *   "status": 400,
 *   "error": "Bad Request",
 *   "message": "msg: 消息内容不能为空",
 *   "timestamp": "2026-05-30T12:00:00Z"
 * }
 *
 * of() 工厂方法自动填充当前时间戳。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
public record ErrorResponse(
        int status,
        String error,
        String message,
        Instant timestamp
) {
    public static ErrorResponse of(int status, String error, String message) {
        return new ErrorResponse(status, error, message, Instant.now());
    }
}
