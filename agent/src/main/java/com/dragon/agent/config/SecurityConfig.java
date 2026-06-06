package com.dragon.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.WebSessionServerSecurityContextRepository;

/**
 * WebFlux 安全配置。
 *
 * <p>使用 WebSession 存储 SecurityContext（cookie 形式），与 Vite 代理同源，
 * cookie 自动携带。CSRF 关闭——SPA + JSON API + session cookie 模式，
 * formLogin / httpBasic 关闭——使用自定义 AuthController 处理登录逻辑。</p>
 *
 * <p>角色权限控制由各 Controller 和 Service 在代码层面实现，
 * 不依赖方法级注解（Spring Boot 4.x / Security 7.x 尚未提供稳定支持）。</p>
 *
 * @author 陈龙
 * @since 2026-06-01
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public WebSessionServerSecurityContextRepository securityContextRepository() {
        return new WebSessionServerSecurityContextRepository();
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
            CustomReactiveAuthenticationManager authManager,
            WebSessionServerSecurityContextRepository securityContextRepository,
            AuthTokenWebFilter authTokenWebFilter) {

        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .addFilterAt(authTokenWebFilter,
                        org.springframework.security.config.web.server.SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(auth -> auth.pathMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/auth/register").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/auth/me").permitAll().pathMatchers("/actuator/health")
                        .permitAll().anyExchange().authenticated())
                .authenticationManager(authManager).securityContextRepository(securityContextRepository)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable).logout(ServerHttpSecurity.LogoutSpec::disable)
                .build();
    }
}
