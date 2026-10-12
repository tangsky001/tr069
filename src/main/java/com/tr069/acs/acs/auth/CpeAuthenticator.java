package com.tr069.acs.acs.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CPE -> ACS 的 HTTP 认证。
 *
 * <p>对应 Python 版 {@code lib/acss/acs/cpeauth.py}。</p>
 *
 * <p><b>重要：本实现刻意保留了原版 Digest 计算的一处非标准行为。</b>
 * 当 CPE 请求中<b>不带 qop</b> 时，原版按 {@code H(HA1 + ":" + nonce + HA2)} 计算
 * （nonce 与 HA2 之间缺少 RFC 2617 要求的英文冒号），而非标准的
 * {@code H(HA1 + ":" + nonce + ":" + HA2)}。由于现网设备是按原版行为实现的，
 * 这里必须保持一致，否则设备无法通过认证。带 qop 的分支与原版及 RFC 一致。</p>
 */
@Slf4j
@Component
public class CpeAuthenticator {

    /**
     * 认证结果，对应原版的 ACS_AUTHENTICATE_PASS / ACS_NO_AUTHENTICATION / ACS_AUTHENTICATE_FAIL。
     */
    public enum AuthResult {
        /**
         * 认证通过
         */
        PASS,
        /**
         * 请求中未携带认证头，已下发 challenge，需等待下一次请求
         */
        NO_AUTHENTICATION,
        /**
         * 认证失败
         */
        FAIL
    }

    /**
     * 默认 Digest realm，对应原版 dict_acs_option.get('realm', 'tr069')
     */
    public static final String DEFAULT_DIGEST_REALM = "tr069";

    /**
     * Basic 认证的 realm
     */
    public static final String BASIC_REALM = "tr069 basic realm";

    /**
     * 认证类型：digest / basic / None
     */
    public static final String AUTH_TYPE_DIGEST = "digest";

    public static final String AUTH_TYPE_BASIC = "basic";

    public static final String AUTH_TYPE_NONE = "None";

    /**
     * 校验 CPE 请求。
     *
     * @param authorization      HTTP Authorization 头，可能为 null
     * @param method             HTTP 方法
     * @param uri                请求 URI
     * @param expectedUser       期望的用户名（通常为设备级配置）
     * @param expectedPassword   期望的密码
     * @param fallbackUser       回落用户名（全局默认账号）
     * @param fallbackPassword   回落密码
     * @param authType           认证类型
     * @return 认证结果
     */
    public AuthResult authenticate(String authorization, String method, String uri,
                                    String expectedUser, String expectedPassword,
                                    String fallbackUser, String fallbackPassword,
                                    String authType) {
        if (AUTH_TYPE_NONE.equalsIgnoreCase(authType)) {
            return AuthResult.PASS;
        }
        if (AUTH_TYPE_BASIC.equalsIgnoreCase(authType)) {
            return authenticateBasic(authorization, expectedUser, expectedPassword,
                    fallbackUser, fallbackPassword);
        }
        return authenticateDigest(authorization, method, uri, expectedUser, expectedPassword,
                fallbackUser, fallbackPassword);
    }

    /**
     * Basic 认证：b64(username:password) 直接比较。
     */
    private AuthResult authenticateBasic(String authorization,
                                         String user, String password,
                                         String fallbackUser, String fallbackPassword) {
        if (authorization == null || authorization.isBlank()) {
            return AuthResult.NO_AUTHENTICATION;
        }
        int sp = authorization.indexOf(' ');
        if (sp < 0 || !authorization.substring(0, sp).equalsIgnoreCase("Basic")) {
            return AuthResult.FAIL;
        }
        String message = authorization.substring(sp + 1).trim();
        if (checkBasic(user, password, message) || checkBasic(fallbackUser, fallbackPassword, message)) {
            return AuthResult.PASS;
        }
        return AuthResult.FAIL;
    }

    private boolean checkBasic(String user, String password, String message) {
        String credential = Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
        return credential.equals(message);
    }

    /**
     * Digest 认证。
     */
    private AuthResult authenticateDigest(String authorization, String method, String uri,
                                          String user, String password,
                                          String fallbackUser, String fallbackPassword) {
        if (authorization == null || authorization.isBlank()) {
            return AuthResult.NO_AUTHENTICATION;
        }
        Map<String, String> fields = parseAuthFields(authorization);
        String type = fields.get("digest_type");
        if (type == null || !type.equalsIgnoreCase("Digest")) {
            return AuthResult.FAIL;
        }
        fields.put("method", method);
        fields.put("uri", uri);

        if (tryUsernamePassword(fields, user, password)) {
            return AuthResult.PASS;
        }
        if (tryUsernamePassword(fields, fallbackUser, fallbackPassword)) {
            return AuthResult.PASS;
        }
        return AuthResult.FAIL;
    }

    /**
     * 对应原版 try_username_password：先比用户名，再比 realm 与 response。
     */
    private boolean tryUsernamePassword(Map<String, String> fields, String user, String password) {
        if (user == null || !user.equals(fields.get("username"))) {
            return false;
        }
        String realm = fields.get("realm");
        if (!DEFAULT_DIGEST_REALM.equals(realm)) {
            return false;
        }
        String expected = createResponse(user, password, realm, fields);
        return expected != null && expected.equals(fields.get("response"));
    }

    /**
     * 计算期望的 response。
     *
     * <p>对应原版 create_response。注意无 qop 分支沿用原版缺少冒号的写法。</p>
     */
    private String createResponse(String user, String password, String realm,
                                  Map<String, String> fields) {
        String ha1 = md5(a1(fields, user, password, realm));
        String ha2 = md5(a2(fields));

        String qop = fields.get("qop");
        if (qop == null || qop.isEmpty()) {
            // 原版写法：nonce 与 HA2 之间没有冒号
            return md5(ha1 + ":" + fields.get("nonce") + ha2);
        }
        if ("auth".equals(qop) || "auth-int".equals(qop)) {
            String nonce = fields.get("nonce");
            String nc = fields.get("nc");
            String cnonce = fields.get("cnonce");
            String data = nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2;
            return md5(ha1 + ":" + data);
        }
        return null;
    }

    /**
     * 对应原版 A1。
     */
    private String a1(Map<String, String> fields, String user, String password, String realm) {
        String algorithm = fields.get("algorithm");
        if ("MD5-sess".equals(algorithm)) {
            String base = md5(user + ":" + realm + ":" + password);
            return base + ":" + fields.get("nonce") + ":" + fields.get("cnonce");
        }
        return user + ":" + realm + ":" + password;
    }

    /**
     * 对应原版 A2。
     */
    private String a2(Map<String, String> fields) {
        String method = fields.getOrDefault("method", "");
        String uri = fields.getOrDefault("uri", "");
        String qop = fields.get("qop");
        if (qop == null || "auth".equals(qop)) {
            return method + ":" + uri;
        }
        // auth-int 分支原版存在实现缺陷（引用了未定义的 body），此处按 method:uri:H(body) 语义保留
        String body = fields.getOrDefault("body", "");
        return method + ":" + uri + ":" + md5(body);
    }

    /**
     * 构造 Digest challenge 响应头。
     *
     * <pre>
     * Digest realm="tr069", nonce="&lt;md5(秒级时间戳:realm)&gt;", opaque="&lt;md5(秒级时间戳+realm)&gt;", qop="auth"
     * </pre>
     */
    public String digestChallenge() {
        String realm = DEFAULT_DIGEST_REALM;
        String nonce = md5(currentSeconds() + ":" + realm);
        String opaque = md5(currentSeconds() + realm);
        return String.format("Digest realm=\"%s\", nonce=\"%s\", opaque=\"%s\", qop=\"%s\"",
                realm, nonce, opaque, "auth");
    }

    /**
     * 构造 Basic challenge 响应头。
     */
    public String basicChallenge() {
        return String.format("Basic realm=\"%s\"", BASIC_REALM);
    }

    private long currentSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    /**
     * 解析 Authorization 头为字段表。
     *
     * <p>对应原版 get_data：以第一个空格分割出认证类型，其余按逗号切分，
     * 带引号取值内容，不带引号直接取值。</p>
     */
    public Map<String, String> parseAuthFields(String authorization) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (authorization == null || authorization.isEmpty()) {
            return fields;
        }
        int sp = authorization.indexOf(' ');
        if (sp < 0) {
            return fields;
        }
        fields.put("digest_type", authorization.substring(0, sp).trim());

        for (String part : splitTopLevel(authorization.substring(sp + 1))) {
            String x = part.trim();
            if (x.isEmpty()) {
                continue;
            }
            int eq = x.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = x.substring(0, eq).trim();
            String value;
            if (x.indexOf('"') >= 0) {
                int begin = eq + 2;
                value = x.length() > begin + 1 ? x.substring(begin, x.length() - 1) : "";
            } else {
                value = x.substring(eq + 1).trim();
            }
            fields.put(key, value);
        }
        return fields;
    }

    /**
     * 按逗号切分，忽略引号内的逗号。
     */
    private static java.util.List<String> splitTopLevel(String input) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
                current.append(c);
            } else if (c == ',' && !inQuote) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 算法不可用", e);
        }
    }
}
