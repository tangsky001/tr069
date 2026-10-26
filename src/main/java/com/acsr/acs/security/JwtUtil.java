package com.acsr.acs.security;

import com.acsr.acs.config.AcsProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/** JWT 工具（管理端登录令牌）。 */
@Component
@RequiredArgsConstructor
public class JwtUtil {

  private final AcsProperties properties;

  private SecretKey key() {
    return Keys.hmacShaKeyFor(properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
  }

  public String generate(String subject, Map<String, Object> claims) {
    long now = System.currentTimeMillis();
    return Jwts.builder()
        .claims(claims)
        .subject(subject)
        .issuedAt(new Date(now))
        .expiration(new Date(now + properties.getJwt().getExpireSeconds() * 1000))
        .signWith(key())
        .compact();
  }

  public Claims parse(String token) {
    return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
  }

  public boolean valid(String token) {
    try {
      return parse(token).getExpiration().after(new Date());
    } catch (Exception e) {
      return false;
    }
  }
}
