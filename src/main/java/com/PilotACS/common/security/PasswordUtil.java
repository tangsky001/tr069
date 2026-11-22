/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** 密码加盐哈希工具 */
public final class PasswordUtil {

    private PasswordUtil() {
    }

    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int PBKDF2_KEY_BITS = 256;
    private static final String PBKDF2_PREFIX = "pbkdf2$";
    /** PBKDF2 固定域盐 */
    private static final String PBKDF2_DOMAIN = "pilotacs.v1";

    public static String randomSalt() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 旧版 SHA-256 加密 */
    public static String encrypt(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((salt + password).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("密码加密失败", e);
        }
    }

    /** PBKDF2 加密 */
    public static String hash(String password, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(),
                    (PBKDF2_DOMAIN + ":" + salt).getBytes(StandardCharsets.UTF_8),
                    PBKDF2_ITERATIONS, PBKDF2_KEY_BITS);
            byte[] dk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            StringBuilder sb = new StringBuilder(PBKDF2_PREFIX);
            for (byte b : dk) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("密码加密失败", e);
        }
    }

    /** 校验口令 */
    public static boolean verify(String password, String salt, String stored) {
        if (stored == null || stored.isBlank()) {
            return false;
        }
        return stored.startsWith(PBKDF2_PREFIX)
                ? constantTimeEquals(hash(password, salt), stored)
                : constantTimeEquals(encrypt(password, salt), stored);
    }

    /** 是否为旧格式 */
    public static boolean isLegacy(String stored) {
        return stored != null && !stored.startsWith(PBKDF2_PREFIX);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** 随机令牌 */
    public static String randomToken() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
