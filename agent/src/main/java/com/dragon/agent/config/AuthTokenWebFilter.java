package com.dragon.agent.config;

import java.util.List;

import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.dragon.agent.service.TokenService;

import reactor.core.publisher.Mono;

/**
 * 认证 Token WebFilter —— 自动通过 AUTH_TOKEN cookie 恢复登录会话。
 *
 * 运行时机：每个 HTTP 请求进入 Security 过滤链之前。
 * 当 WebSession 中的 SecurityContext 丢失时（如后端重启），
 * 此 Filter 验证持久 cookie 并自动重建 SecurityContext。
 *
 * AUTH_TOKEN cookie 是 session cookie，浏览器关闭后自动删除，
 * 确保"浏览器不关就保持登录"的需求。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
@Component
public class AuthTokenWebFilter implements WebFilter {

    private static final String TOKEN_COOKIE = "AUTH_TOKEN";

    private final TokenService tokenService;
    private final ServerSecurityContextRepository securityContextRepository;

    public AuthTokenWebFilter(TokenService tokenService,
                              ServerSecurityContextRepository securityContextRepository) {
        this.tokenService = tokenService;
        this.securityContextRepository = securityContextRepository;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return securityContextRepository.load(exchange)
                .filter(ctx -> ctx.getAuthentication() != null
                        && ctx.getAuthentication().isAuthenticated())
                .switchIfEmpty(
                    // 当前无有效 SecurityContext → 尝试通过 AUTH_TOKEN 恢复
                    tryRestoreFromToken(exchange)
                        .flatMap(ctx -> securityContextRepository.save(exchange, ctx))
                        .then(Mono.empty())
                )
                .then(chain.filter(exchange));
    }

    /**
     * 从 AUTH_TOKEN cookie 验证并重建 SecurityContext。
     */
    private Mono<SecurityContext> tryRestoreFromToken(ServerWebExchange exchange) {
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst(TOKEN_COOKIE);
        if (cookie == null) {
            return Mono.empty();
        }
        return Mono.justOrEmpty(tokenService.validateToken(cookie.getValue()))
                .map(this::buildSecurityContext)
                .doOnSuccess(ctx -> {
                    // Token 验证成功，刷新 cookie（防过期）
                    if (ctx != null) {
                        exchange.getResponse().addCookie(createTokenCookie(
                                tokenService.generateToken(ctx.getAuthentication().getName())));
                    }
                });
    }

    /**
     * 根据用户名构建 SecurityContext。
     */
    private SecurityContext buildSecurityContext(String username) {
        UserDetails user = User.builder()
                .username(username)
                .password("") // token 认证无需密码
                .authorities("ROLE_USER")
                .build();
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        return new SecurityContextImpl(auth);
    }

    /**
     * 创建 AUTH_TOKEN session cookie。
     */
    public static ResponseCookie createTokenCookie(String token) {
        return ResponseCookie.from(TOKEN_COOKIE, token)
                .path("/")
                .httpOnly(true)
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
