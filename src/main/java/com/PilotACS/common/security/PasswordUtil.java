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

// 密码加盐哈希工具
public final class PasswordUtil {

    private PasswordUtil() {
    }

    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int PBKDF2_KEY_BITS = 256;
    private static final String PBKDF2_PREFIX = "pbkdf2$";
    /** PBKDF2 盐里混入应用固定域，避免与其他系统的同 salt+password 口令撞库 */
    private static final String PBKDF2_DOMAIN = "pilotacs.v1";

    public static String randomSalt() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 旧版单轮 SHA-256（存量账号兼容，不再用于新口令） */
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

    /** 新版 PBKDF2 慢哈希（新口令一律用这个） */
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

    /** 校验口令：按存量格式自动选择算法（pbkdf2$ 前缀 → PBKDF2；否则旧 SHA-256） */
    public static boolean verify(String password, String salt, String stored) {
        if (stored == null || stored.isBlank()) {
            return false;
        }
        return stored.startsWith(PBKDF2_PREFIX)
                ? constantTimeEquals(hash(password, salt), stored)
                : constantTimeEquals(encrypt(password, salt), stored);
    }

    /** 存量是否为旧格式（登录校验通过后据此透明升级） */
    public static boolean isLegacy(String stored) {
        return stored != null && !stored.startsWith(PBKDF2_PREFIX);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** 随机字节（会话/重置令牌用），保留给后续扩展 */
    public static String randomToken() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
