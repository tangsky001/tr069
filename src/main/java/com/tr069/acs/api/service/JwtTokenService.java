package com.tr069.acs.api.service;

import com.tr069.acs.common.security.JwtSecretProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Date;

/**
 * 管理端令牌服务。
 *
 * <p>令牌通过 X-Token 头传递，与 vue-admin-template 的 request 拦截器一致。
 * 签名密钥由 {@link JwtSecretProvider} 提供，不取自配置文件。</p>
 */
@Slf4j
@Component
public class JwtTokenService {

    private final SecretKey key;

    private final long expireHours;

    public JwtTokenService(JwtSecretProvider secretProvider,
                           @Value("${admin.jwt.expire-hours:12}") long expireHours) {
        this.expireHours = expireHours;
        this.key = secretProvider.getKey();
    }

    public String generate(String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + Duration.ofHours(expireHours).toMillis());
        return Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /**
     * 解析令牌中的用户名，失败返回 null。
     */
    public String parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.getSubject();
        } catch (Exception e) {
            return null;
        }
    }

    public boolean validate(String token) {
        return token != null && parse(token) != null;
    }
}
