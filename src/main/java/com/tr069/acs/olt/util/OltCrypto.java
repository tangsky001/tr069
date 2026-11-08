package com.tr069.acs.olt.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * OLT 设备密码对称加密：AES-GCM。
 * <p>
 * 密文以 "enc:" 前缀标识，没有前缀的按明文处理（兼容存量数据）。
 * 密钥从环境变量 {@code ACS_OLT_CRYPTO_KEY} 读取，默认用内置密钥（仅用于开发环境）。
 */
public final class OltCrypto {

    private static final String PREFIX = "enc:";
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private static final byte[] DEFAULT_KEY = "tr069-acs-olt-k!".getBytes(StandardCharsets.UTF_8);

    private static final byte[] KEY;

    static {
        String envKey = System.getenv("ACS_OLT_CRYPTO_KEY");
        byte[] raw = envKey != null && envKey.length() == 16
                ? envKey.getBytes(StandardCharsets.UTF_8)
                : DEFAULT_KEY;
        // 补齐到 16 字节（AES-128）
        if (raw.length < 16) {
            byte[] padded = new byte[16];
            System.arraycopy(raw, 0, padded, 0, raw.length);
            raw = padded;
        } else if (raw.length > 16) {
            byte[] trimmed = new byte[16];
            System.arraycopy(raw, 0, trimmed, 0, 16);
            raw = trimmed;
        }
        KEY = raw;
    }

    private OltCrypto() {
    }

    /** 加密：返回带前缀的密文字符串。null 或空串原样返回 */
    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        // 已经是密文了，不重复加密
        if (plain.startsWith(PREFIX)) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, ALGORITHM), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("OLT 密码加密失败", e);
        }
    }

    /** 解密：传入密文返回明文。没有前缀的按明文直接返回（兼容存量数据） */
    public static String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        if (!stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[IV_BYTES];
            byte[] cipherText = new byte[combined.length - IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_BYTES);
            System.arraycopy(combined, IV_BYTES, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, ALGORITHM), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(cipherText);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("OLT 密码解密失败", e);
        }
    }

    /** 判断存储的密码是否已加密 */
    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }
}
