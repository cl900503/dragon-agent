package com.dragon.agent.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;

import com.dragon.agent.dto.ErrorResponse;

/**
 * 全局异常处理——统一所有接口的错误响应格式，避免堆栈信息泄漏到前端。
 *
 * WebFlux 的参数校验失败抛出 WebExchangeBindException（不是 Servlet 栈的
 * MethodArgumentNotValidException），两个异常类型不同，不要混用。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理 @Valid 校验失败（如 msg 字段为空）。
     * 提取所有字段错误拼接成可读消息返回。
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ErrorResponse> handleValidation(WebExchangeBindException ex) {
        String msg = ex.getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(400, "Bad Request", msg));
    }

    /**
     * 兜底处理——捕获所有未被上层精确匹配的异常。
     * 记录完整堆栈到日志，对外只返回模糊提示，不泄漏内部细节。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("未处理的异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(500, "Internal Server Error", "服务器内部错误，请稍后重试"));
    }
}
