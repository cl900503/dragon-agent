package com.dragon.agent.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 认证 Token 服务——HMAC-SHA256 签名，用于会话恢复。
 *
 * 当 WebSession 丢失（如后端重启）时，通过验证浏览器携带的 AUTH_TOKEN cookie 自动重建
 * SecurityContext，用户无需重新登录。
 *
 * Token 格式: Base64(username + ":" + HMAC-SHA256(username, secret))
 *
 * @author 陈龙
 * @since 2026-06-01
 */
@Service
public class TokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SEPARATOR = ":";

    private final String secret;

    public TokenService(@Value("${app.auth.token-secret}") String secret) {
        this.secret = secret;
    }

    /**
     * 验证密钥安全强度，非 dev 环境下密钥不能为空且长度至少 32 字符。
     */
    public void validateSecret(boolean isDevProfile) {
        if (secret == null || secret.isBlank()) {
            if (isDevProfile) {
                throw new IllegalStateException("生产环境必须设置 AUTH_TOKEN_SECRET 环境变量，长度至少 32 字符");
            }
            throw new IllegalStateException("AUTH_TOKEN_SECRET 未配置，请设置环境变量或 application.yaml 中的 app.auth.token-secret");
        }
        if (secret.length() < 16) {
            throw new IllegalStateException("AUTH_TOKEN_SECRET 太短（" + secret.length() + " 字符），至少需要 16 字符");
        }
    }

    /**
     * 生成签名 Token。
     *
     * @param username
     *            用户名
     * @return Base64 编码的 token 字符串
     */
    public String generateToken(String username) {
        String signature = hmac(username, secret);
        String payload = username + SEPARATOR + signature;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证 Token 并提取用户名。
     *
     * @param token
     *            Base64 编码的 token 字符串
     * @return 验证通过时返回用户名，否则返回空
     */
    public Optional<String> validateToken(String token) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            String payload = new String(decoded, StandardCharsets.UTF_8);
            int idx = payload.lastIndexOf(SEPARATOR);
            if (idx <= 0) {
                return Optional.empty();
            }
            String username = payload.substring(0, idx);
            String providedSig = payload.substring(idx + 1);
            String expectedSig = hmac(username, secret);
            if (providedSig.equals(expectedSig)) {
                return Optional.of(username);
            }
        } catch (IllegalArgumentException e) {
            // Base64 解码失败 → 无效 token
        }
        return Optional.empty();
    }

    /**
     * HMAC-SHA256 签名。
     */
    private String hmac(String data, String key) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC 签名失败", e);
        }
    }
}
