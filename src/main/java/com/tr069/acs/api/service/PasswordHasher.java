package com.tr069.acs.api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * 管理端口令散列。
 *
 * <p>使用 PBKDF2WithHmacSHA256，JDK 自带算法，不引入额外依赖。
 * 存储格式为 {@code pbkdf2$迭代次数$盐(base64)$散列(base64)}。</p>
 *
 * <p>为了平滑迁移，校验时若遇到历史明文口令，仍按明文比对并放行，
 * 由 {@code AuthController} 在登录成功后自动升级为散列值。</p>
 */
@Slf4j
@Component
public class PasswordHasher {

    private static final String PREFIX = "pbkdf2";
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final SecureRandom random = new SecureRandom();

    /**
     * 生成口令散列。
     */
    public String hash(String raw) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = pbkdf2(raw.toCharArray(), salt, ITERATIONS, KEY_BITS);
        return PREFIX + "$" + ITERATIONS + "$" + b64(salt) + "$" + b64(hash);
    }

    /**
     * 校验明文口令与库中存储值是否匹配。
     */
    public boolean matches(String raw, String stored) {
        if (raw == null || stored == null) {
            return false;
        }
        if (!isHashed(stored)) {
            return raw.equals(stored);
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4) {
            return false;
        }
        int iterations;
        try {
            iterations = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        byte[] salt;
        byte[] expected;
        try {
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] actual = pbkdf2(raw.toCharArray(), salt, iterations, expected.length * 8);
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * 存储值是否已是散列格式（false 表示历史明文口令，需要升级）。
     */
    public boolean isHashed(String stored) {
        return stored != null && stored.startsWith(PREFIX + "$");
    }

    private byte[] pbkdf2(char[] raw, byte[] salt, int iterations, int keyBits) {
        PBEKeySpec spec = new PBEKeySpec(raw, salt, iterations, keyBits);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("口令散列计算失败", e);
        } finally {
            spec.clearPassword();
        }
    }

    private String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }
}
