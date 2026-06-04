package com.dragon.agent.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.dragon.agent.config.AuthTokenWebFilter;
import com.dragon.agent.dto.AuthResponse;
import com.dragon.agent.dto.LoginRequest;
import com.dragon.agent.dto.RegisterRequest;
import com.dragon.agent.exception.UsernameAlreadyExistsException;
import com.dragon.agent.repository.UserRepository;
import com.dragon.agent.service.TokenService;
import com.dragon.agent.service.UserService;

import jakarta.validation.Valid;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 认证接口——注册（管理员创建用户）、登录、登出和会话检查。
 *
 * 注册仅允许 ADMIN 角色调用。首个管理员需在数据库空时直接注册，
 * 之后通过 /api/admin/users 接口管理用户。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private ReactiveAuthenticationManager authenticationManager;

    @Autowired
    private ServerSecurityContextRepository securityContextRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.dragon.agent.support.SecurityHelper securityHelper;

    /**
     * 注册——DB 空时首个用户自动成为系统管理员，之后仅 ADMIN 可创建用户。
     */
    @PostMapping("/register")
    public Mono<ResponseEntity<AuthResponse>> register(@Valid @RequestBody RegisterRequest request,
            ServerWebExchange exchange) {
        boolean isFirstUser = userRepository.count() == 0;

        if (!isFirstUser) {
            return securityHelper.currentUsername()
                    .flatMap(currentUser -> {
                        var adminUser = userRepository.findByUsername(currentUser).orElse(null);
                        if (adminUser == null || !"ADMIN".equals(adminUser.getRole())) {
                            return Mono.just(ResponseEntity.status(403)
                                    .body(new AuthResponse(null, "系统已初始化，新账号需由管理员在「管理面板」中创建")));
                        }
                        return doRegister(request, exchange, null);
                    })
                    .switchIfEmpty(Mono.just(ResponseEntity.status(403)
                            .body(new AuthResponse(null, "系统已由管理员接管，新账号请联系管理员创建"))));
        }
        // 首个用户自动设为 ADMIN
        return doRegister(request, exchange, "ADMIN");
    }

    /** 登录 */
    @PostMapping("/login")
    public Mono<ResponseEntity<AuthResponse>> login(@Valid @RequestBody LoginRequest request,
            ServerWebExchange exchange) {
        var token = UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password());
        return authenticationManager.authenticate(token).flatMap(auth -> {
            return exchange.getSession().flatMap(session -> {
                var ctx = new SecurityContextImpl(auth);
                session.getAttributes().put("SPRING_SECURITY_CONTEXT", ctx);
                return securityContextRepository.save(exchange, ctx).thenReturn(session);
            }).then(Mono.fromCallable(() -> {
                UserDetails user = (UserDetails) auth.getPrincipal();
                String tokenValue = tokenService.generateToken(user.getUsername());
                exchange.getResponse().addCookie(AuthTokenWebFilter.createTokenCookie(tokenValue));
                var userEntity = userRepository.findByUsername(user.getUsername()).orElse(null);
                String role = userEntity != null ? userEntity.getRole() : null;
                return ResponseEntity.ok(new AuthResponse(user.getUsername(), role, "登录成功"));
            }).subscribeOn(Schedulers.boundedElastic()));
        }).onErrorResume(AuthenticationException.class,
                e -> Mono.just(ResponseEntity.status(401).body(new AuthResponse(null, "用户名或密码错误"))));
    }

    /** 登出 */
    @PostMapping("/logout")
    public Mono<ResponseEntity<AuthResponse>> logout(ServerWebExchange exchange) {
        return exchange.getSession().flatMap(session -> {
            session.getAttributes().clear();
            return session.invalidate();
        }).then(Mono.fromCallable(() -> {
            exchange.getResponse().addCookie(AuthTokenWebFilter.clearTokenCookie());
            return ResponseEntity.ok(new AuthResponse(null, "已退出"));
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    /** 会话检查 */
    @GetMapping("/me")
    public Mono<ResponseEntity<Map<String, String>>> me() {
        return securityHelper.currentUsername()
                .map(username -> {
                    var user = userRepository.findByUsername(username).orElse(null);
                    String role = user != null && user.getRole() != null ? user.getRole() : "USER";
                    return ResponseEntity.ok(Map.of("username", username, "role", role, "message", "已登录"));
                })
                .switchIfEmpty(Mono.just(ResponseEntity.status(401)
                        .body(Map.of("username", "", "role", "", "message", "未登录"))));
    }

    private Mono<ResponseEntity<AuthResponse>> doRegister(RegisterRequest request, ServerWebExchange exchange,
            String defaultRole) {
        String role = defaultRole != null ? defaultRole : request.role();
        return Mono.fromCallable(() -> userService.register(request.username(), request.password(),
                        request.displayName(), request.email(), role, request.departmentId()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(user -> exchange.getSession().flatMap(session -> {
                    var auth = new UsernamePasswordAuthenticationToken(user, null,
                            List.of(new SimpleGrantedAuthority("ROLE_USER")));
                    var ctx = new SecurityContextImpl(auth);
                    session.getAttributes().put("SPRING_SECURITY_CONTEXT", ctx);
                    return securityContextRepository.save(exchange, ctx).thenReturn(session);
                }).then(Mono.fromCallable(() -> {
                    String tokenValue = tokenService.generateToken(user.getUsername());
                    exchange.getResponse().addCookie(AuthTokenWebFilter.createTokenCookie(tokenValue));
                    return ResponseEntity.status(201)
                            .body(new AuthResponse(user.getUsername(), role, "注册成功"));
                }).subscribeOn(Schedulers.boundedElastic())))
                .onErrorResume(UsernameAlreadyExistsException.class,
                        e -> Mono.just(ResponseEntity.status(409).body(new AuthResponse(null, e.getMessage()))));
    }
}
