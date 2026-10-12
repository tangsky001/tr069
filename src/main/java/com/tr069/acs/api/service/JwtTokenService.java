package com.tr069.acs.api.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

/**
 * 管理端令牌服务。
 *
 * <p>令牌通过 X-Token 头传递，与 vue-admin-template 的 request 拦截器一致。</p>
 */
@Slf4j
@Component
public class JwtTokenService {

    private final SecretKey key;

    private final long expireHours;

    public JwtTokenService(@Value("${admin.jwt.secret}") String secret,
                           @Value("${admin.jwt.expire-hours:12}") long expireHours) {
        this.expireHours = expireHours;
        // 密钥按 UTF-8 原样取字节，HS256 要求不短于 32 字节，不足时右侧补零
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = raw.length >= 32 ? raw : java.util.Arrays.copyOf(raw, 32);
        this.key = Keys.hmacShaKeyFor(keyBytes);
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
            log.debug("令牌解析失败: {}", e.getMessage());
            return null;
        }
    }

    public boolean validate(String token) {
        return token != null && parse(token) != null;
    }
}
