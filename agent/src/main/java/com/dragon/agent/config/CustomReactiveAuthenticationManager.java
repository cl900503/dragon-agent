package com.dragon.agent.config;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import com.dragon.agent.service.UserService;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 自定义响应式认证管理器——从 MySQL 加载用户并校验密码。
 *
 * 密码校验走 boundedElastic，因为 BCrypt.matches() 是阻塞操作。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
@Component
public class CustomReactiveAuthenticationManager implements ReactiveAuthenticationManager {

    private final UserService userService;

    public CustomReactiveAuthenticationManager(UserService userService) {
        this.userService = userService;
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        String username = authentication.getName();
        String password = authentication.getCredentials().toString();

        return Mono.fromCallable(() -> {
            UserDetails user = userService.findByUsername(username);
            if (user == null) {
                throw new BadCredentialsException("用户名或密码错误");
            }
            if (!userService.passwordMatches(password, user.getPassword())) {
                throw new BadCredentialsException("用户名或密码错误");
            }
            return (Authentication) new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
