package com.dragon.agent.config;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.dragon.agent.entity.UserEntity;
import com.dragon.agent.repository.UserRepository;
import com.dragon.agent.service.TokenService;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 认证 Token WebFilter —— 自动通过 AUTH_TOKEN cookie 恢复登录会话。
 *
 * <p>每个 HTTP 请求进入 Security 过滤链之前，当 WebSession 中的 SecurityContext
 * 丢失时（如后端重启），此 Filter 验证持久 cookie 并从数据库加载用户实际角色后
 * 重建 SecurityContext。</p>
 *
 * <p>AUTH_TOKEN cookie 为 session cookie，浏览器关闭后自动删除。</p>
 *
 * @author 陈龙
 * @since 2026-06-01
 */
@Component
public class AuthTokenWebFilter implements WebFilter {

    private static final String TOKEN_COOKIE = "AUTH_TOKEN";

    @Autowired
    private TokenService tokenService;

    @Autowired
    private ServerSecurityContextRepository securityContextRepository;

    @Autowired
    private UserRepository userRepository;

    @Value("${app.auth.cookie-secure:false}")
    private boolean secureCookie;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return securityContextRepository.load(exchange)
                .filter(ctx -> ctx.getAuthentication() != null && ctx.getAuthentication().isAuthenticated())
                .switchIfEmpty(tryRestoreFromToken(exchange)
                        .flatMap(ctx -> securityContextRepository.save(exchange, ctx))
                        .then(Mono.empty()))
                .then(chain.filter(exchange));
    }

    /**
     * 从 AUTH_TOKEN cookie 验证并重建 SecurityContext。
     *
     * <p>token 验证通过后从数据库加载用户实体，使用用户实际角色构建权限，
     * 而非硬编码 ROLE_USER。</p>
     */
    private Mono<SecurityContext> tryRestoreFromToken(ServerWebExchange exchange) {
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst(TOKEN_COOKIE);
        if (cookie == null) {
            return Mono.empty();
        }
        return Mono.justOrEmpty(tokenService.validateToken(cookie.getValue()))
                .flatMap(username -> Mono.fromCallable(
                        () -> userRepository.findByUsername(username).orElse(null))
                        .subscribeOn(Schedulers.boundedElastic()))
                .filter(Objects::nonNull)
                .map(this::buildSecurityContext)
                .doOnSuccess(ctx -> {
                    if (ctx != null) {
                        exchange.getResponse().addCookie(createTokenCookie(
                                tokenService.generateToken(ctx.getAuthentication().getName()),
                                secureCookie));
                    }
                });
    }

    /**
     * 根据用户实体构建 SecurityContext，使用数据库中的实际角色。
     */
    private SecurityContext buildSecurityContext(UserEntity user) {
        String role = user.getRole() != null ? user.getRole() : "USER";
        List<SimpleGrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_" + role));
        UserDetails userDetails = User.builder()
                .username(user.getUsername())
                .password("")
                .authorities(authorities)
                .build();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                userDetails, null, authorities);
        return new SecurityContextImpl(auth);
    }

    /**
     * 创建 AUTH_TOKEN session cookie。
     *
     * @param token  签名的 token 字符串
     * @param secure 是否设置 Secure 标志（生产环境应为 true）
     */
    public static ResponseCookie createTokenCookie(String token, boolean secure) {
        return ResponseCookie.from(TOKEN_COOKIE, token)
                .path("/")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .build();
    }

    /**
     * 创建清除用的 AUTH_TOKEN cookie。
     */
    public static ResponseCookie clearTokenCookie() {
        return ResponseCookie.from(TOKEN_COOKIE, "")
                .path("/")
                .httpOnly(true)
                .sameSite("Lax")
                .maxAge(0)
                .build();
    }
}
