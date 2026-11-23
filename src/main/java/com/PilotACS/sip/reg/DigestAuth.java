/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.reg;

import com.PilotACS.sip.core.SipMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** SIP Digest 401 鉴权（MD5，支持 qop=auth 与无 qop 两种回包） */
public final class DigestAuth {

    /** nonce → 签发时间（防重放） */
    private final Map<String, Long> nonces = new ConcurrentHashMap<>();

    public String newNonce() {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        nonces.put(nonce, System.currentTimeMillis());
        // 顺手清理 5 分钟前的旧 nonce
        nonces.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue() > 300_000);
        return nonce;
    }

    /** WWW-Authenticate 头值 */
    public String challenge(String realm, String nonce) {
        return "Digest realm=\"" + realm + "\", nonce=\"" + nonce + "\", algorithm=MD5";
    }

    /** 校验 Authorization 头，密码错误/缺参返回 false */
    public boolean verify(SipMessage msg, String method, String realm, String expectedPassword) {
        String auth = msg.header("Authorization");
        if (auth == null || !auth.startsWith("Digest")) {
            return false;
        }
        Map<String, String> p = parseParams(auth);
        String username = p.get("username");
        String nonce = p.get("nonce");
        String response = p.get("response");
        String uri = p.get("uri");
        if (username == null || nonce == null || response == null || !nonces.containsKey(nonce)) {
            return false;
        }
        String ha1 = md5(username + ":" + realm + ":" + expectedPassword);
        String ha2 = md5(method + ":" + (uri == null ? "" : uri));
        String qop = p.get("qop");
        String expected;
        if (qop != null && qop.toLowerCase().contains("auth")) {
            String nc = p.getOrDefault("nc", "00000001");
            String cnonce = p.getOrDefault("cnonce", "");
            expected = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);
        } else {
            expected = md5(ha1 + ":" + nonce + ":" + ha2);
        }
        return expected.equalsIgnoreCase(response);
    }

    /** 解析 Digest 参数（引号可含逗号） */
    private Map<String, String> parseParams(String auth) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        String body = auth.substring(auth.indexOf("Digest") + 6);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]+))")
                .matcher(body);
        while (m.find()) {
            out.put(m.group(1), m.group(2) != null ? m.group(2) : m.group(3));
        }
        return out;
    }

    private String md5(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }
}
