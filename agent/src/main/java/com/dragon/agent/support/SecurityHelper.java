package com.dragon.agent.support;

import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * Security 工具组件——从响应式 SecurityContext 中提取当前登录用户名。
 *
 * 消除各 Controller 中重复的 currentUsername() 私有方法。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
@Component
public class SecurityHelper {

    /**
     * 从当前响应式请求上下文中获取已认证用户名。
     *
     * @return 包含用户名的 Mono，未认证时 Mono 为空
     */
    public Mono<String> currentUsername() {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication().getName());
    }
}
