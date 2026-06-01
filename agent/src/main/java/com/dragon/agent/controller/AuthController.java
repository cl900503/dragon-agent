package com.dragon.agent.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.dragon.agent.dto.AuthResponse;
import com.dragon.agent.dto.LoginRequest;
import com.dragon.agent.dto.RegisterRequest;
import com.dragon.agent.exception.UsernameAlreadyExistsException;
import com.dragon.agent.service.UserService;

import jakarta.validation.Valid;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 认证接口——注册、登录、登出和会话检查。
 *
 * 登录/注册成功后手动将 SecurityContext 写入 WebSession，
 * 浏览器自动收到 SESSION cookie，后续请求自动携带。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final ReactiveAuthenticationManager authManager;
    private final ServerSecurityContextRepository securityContextRepository;

    public AuthController(UserService userService,
                          ReactiveAuthenticationManager authManager,
                          ServerSecurityContextRepository securityContextRepository) {
        this.userService = userService;
        this.authManager = authManager;
        this.securityContextRepository = securityContextRepository;
    }

    /**
     * POST /api/auth/register — 注册新用户，成功后自动登录。
     */
    @PostMapping("/register")
    public Mono<ResponseEntity<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request,
            ServerWebExchange exchange) {
        return Mono.fromCallable(() ->
                        userService.register(request.username(), request.password()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(userDetails -> saveSecurityContext(userDetails, exchange)
                        .thenReturn(ResponseEntity.status(201)
                                .body(new AuthResponse(request.username(), "注册成功"))))
                .onErrorResume(UsernameAlreadyExistsException.class, e ->
                        Mono.just(ResponseEntity.status(409)
                                .body(new AuthResponse(null, e.getMessage()))));
    }

    /**
     * POST /api/auth/login — 验证凭证并建立会话。
     */
    @PostMapping("/login")
    public Mono<ResponseEntity<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request,
            ServerWebExchange exchange) {
        Authentication token = new UsernamePasswordAuthenticationToken(
                request.username(), request.password());
        return authManager.authenticate(token)
                .flatMap(auth -> {
                    UserDetails user = (UserDetails) auth.getPrincipal();
                    return saveSecurityContext(user, exchange)
                            .thenReturn(ResponseEntity.ok(
                                    new AuthResponse(user.getUsername(), "登录成功")));
                })
                .onErrorResume(AuthenticationException.class, e ->
                        Mono.just(ResponseEntity.status(401)
                                .body(new AuthResponse(null, e.getMessage()))));
    }

    /**
     * POST /api/auth/logout — 清除 SecurityContext，失效会话。
     */
    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout(ServerWebExchange exchange) {
        SecurityContext emptyContext = new SecurityContextImpl();
        return securityContextRepository.save(exchange, emptyContext)
                .then(Mono.just(ResponseEntity.ok().build()));
    }

    /**
     * GET /api/auth/me — 检查当前会话状态。
     *
     * 注意：此端点配置为 permitAll，因为未登录时也需要得到明确的 401 响应，
     * 而不是被 Spring Security 的认证过滤器拦截。
     */
    @GetMapping("/me")
    public Mono<ResponseEntity<AuthResponse>> me(ServerWebExchange exchange) {
        return securityContextRepository.load(exchange)
                .filter(ctx -> ctx.getAuthentication() != null
                        && ctx.getAuthentication().isAuthenticated()
                        && ctx.getAuthentication().getPrincipal() instanceof UserDetails)
                .map(ctx -> {
                    UserDetails user = (UserDetails) ctx.getAuthentication().getPrincipal();
                    return ResponseEntity.ok(new AuthResponse(user.getUsername(), "已登录"));
                })
                .defaultIfEmpty(ResponseEntity.status(401)
                        .body(new AuthResponse(null, "未登录")));
    }

    /**
     * 保存已认证的 SecurityContext 到 WebSession。
     */
    private Mono<Void> saveSecurityContext(UserDetails user, ServerWebExchange exchange) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                user, null, user.getAuthorities());
        SecurityContext context = new SecurityContextImpl(auth);
        return securityContextRepository.save(exchange, context);
    }
}
