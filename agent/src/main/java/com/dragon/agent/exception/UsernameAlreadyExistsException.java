package com.dragon.agent.exception;

/**
 * 用户名已存在异常——注册时用户名冲突抛出，由 GlobalExceptionHandler 统一处理。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
public class UsernameAlreadyExistsException extends RuntimeException {

    public UsernameAlreadyExistsException(String message) {
        super(message);
    }
}
