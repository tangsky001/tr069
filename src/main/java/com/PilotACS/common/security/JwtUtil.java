/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.164
 *
 */

package com.PilotACS.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

// JWT 生成与解析；密钥优先环境变量，否则用运行目录下 jwtsecret.xml 密钥文件（缺失则自动生成），不落配置文件
@Component
public class JwtUtil {

    private static final String SECRET_ENV = "ACS_JWT_SECRET";
    private static final String EXPIRE_ENV = "ACS_JWT_EXPIRE_HOURS";
    private static final int MIN_SECRET_LEN = 32;
    // 密钥文件：直接放运行目录（target）下
    private static final Path SECRET_FILE = Path.of("jwtsecret.xml");

    private SecretKey secretKey;
    private long expireHours;

    @PostConstruct
    public void init() {
        String secret = System.getenv(SECRET_ENV);
        if (secret == null || secret.isBlank()) {
            secret = loadOrCreateSecretFile();
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_LEN) {
            throw new IllegalStateException(SECRET_ENV + " 长度不得少于 " + MIN_SECRET_LEN + " 字符");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        String expire = System.getenv(EXPIRE_ENV);
        this.expireHours = (expire == null || expire.isBlank()) ? 12 : Long.parseLong(expire);
    }

    // 读取密钥文件（XML 格式，取 <secret> 内容），不存在则生成 96 位随机密钥并保存
    private String loadOrCreateSecretFile() {
        try {
            if (Files.exists(SECRET_FILE)) {
                String saved = extractSecret(Files.readString(SECRET_FILE));
                if (!saved.isBlank()) {
                    return saved;
                }
            }
            byte[] bytes = new byte[48];
            new SecureRandom().nextBytes(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            String generated = sb.toString();
            Files.writeString(SECRET_FILE, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<jwtSecret>\n    <secret>" + generated + "</secret>\n</jwtSecret>\n");
            return generated;
        } catch (Exception e) {
            throw new IllegalStateException("密钥文件读写失败：" + e.getMessage(), e);
        }
    }

    // 从 XML 内容中提取 <secret> 标签的值
    private static String extractSecret(String xml) {
        int start = xml.indexOf("<secret>");
        int end = xml.indexOf("</secret>");
        if (start < 0 || end <= start) {
            return "";
        }
        return xml.substring(start + "<secret>".length(), end).trim();
    }

    public String create(Long userId, String username, Map<String, Object> claims) {
        return create(userId, username, claims, expireHours);
    }

    // 自定义有效期的签发（用于装维 APP 二维码等长有效期令牌）
    public String create(Long userId, String username, Map<String, Object> claims, long hours) {
        Date now = new Date();
        return Jwts.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .subject(username)
                .claim("userId", userId)
                .claims(claims)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + hours * 3600_000L))
                .signWith(secretKey)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(secretKey).build()
                .parseSignedClaims(token).getPayload();
    }

    public long getExpireHours() {
        return expireHours;
    }
}
