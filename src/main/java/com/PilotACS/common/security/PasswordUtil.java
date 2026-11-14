/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.734
 *
 */

package com.PilotACS.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

// 密码加盐哈希工具
public final class PasswordUtil {

    private PasswordUtil() {
    }

    public static String randomSalt() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

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
}
