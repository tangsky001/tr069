package com.tr069.acs.acs.auth;

import com.tr069.acs.acs.auth.CpeAuthenticator.AuthResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CPE -> ACS 认证校验，重点是免认证（auto / 留空）的判定。
 */
class CpeAuthenticatorTest {

    private static final String URI = "/ACS-server/ACS";

    private static final String REALM = CpeAuthenticator.DEFAULT_DIGEST_REALM;

    private static final String NONCE = "abcdef0123456789";

    private final CpeAuthenticator authenticator = new CpeAuthenticator();

    @Test
    void openAccessWhenUsernameIsAuto() {
        // 不带 Authorization 头也要放行：免认证的设备根本不会走 challenge 流程
        AuthResult result = authenticator.authenticate(null, "POST", URI,
                "auto", "whatever", "auto", "whatever", CpeAuthenticator.AUTH_TYPE_DIGEST);

        assertThat(result).isEqualTo(AuthResult.PASS);
    }

    @Test
    void openAccessWhenPasswordIsAuto() {
        AuthResult result = authenticator.authenticate(null, "POST", URI,
                "hgw", "auto", "hgw", "auto", CpeAuthenticator.AUTH_TYPE_DIGEST);

        assertThat(result).isEqualTo(AuthResult.PASS);
    }

    @Test
    void openAccessWhenCredentialLeftBlank() {
        assertThat(authenticator.authenticate(null, "POST", URI,
                "", "", "", "", CpeAuthenticator.AUTH_TYPE_DIGEST))
                .isEqualTo(AuthResult.PASS);
        assertThat(authenticator.authenticate(null, "POST", URI,
                null, null, null, null, CpeAuthenticator.AUTH_TYPE_DIGEST))
                .isEqualTo(AuthResult.PASS);
        assertThat(authenticator.authenticate(null, "POST", URI,
                "hgw", "", "hgw", "", CpeAuthenticator.AUTH_TYPE_DIGEST))
                .isEqualTo(AuthResult.PASS);
        assertThat(authenticator.authenticate(null, "POST", URI,
                "hgw", "hgw", "hgw", "hgw", CpeAuthenticator.AUTH_TYPE_DIGEST))
                .isEqualTo(AuthResult.NO_AUTHENTICATION);
    }

    @Test
    void openAccessAlsoAppliesToBasic() {
        AuthResult result = authenticator.authenticate(null, "POST", URI,
                "auto", "auto", "auto", "auto", CpeAuthenticator.AUTH_TYPE_BASIC);

        assertThat(result).isEqualTo(AuthResult.PASS);
    }

    /**
     * 配了真实账号密码就必须校验：不带认证头要先 challenge，密码错了要拒。
     */
    @Test
    void realCredentialIsStillVerified() {
        DigestAuth good = digest("hgw", "hgw");
        DigestAuth bad = digest("hgw", "wrong");

        assertThat(authenticator.authenticate(good.header(), "POST", URI,
                "hgw", "hgw", "hgw", "hgw", CpeAuthenticator.AUTH_TYPE_DIGEST))
                .isEqualTo(AuthResult.PASS);
        assertThat(authenticator.authenticate(bad.header(), "POST", URI,
                "hgw", "hgw", "hgw", "hgw", CpeAuthenticator.AUTH_TYPE_DIGEST))
                .isEqualTo(AuthResult.FAIL);
    }

    @Test
    void basicCredentialIsVerified() {
        String header = "Basic " + Base64.getEncoder().encodeToString(
                "hgw:hgw".getBytes(StandardCharsets.UTF_8));

        assertThat(authenticator.authenticate(header, "POST", URI,
                "hgw", "hgw", "hgw", "hgw", CpeAuthenticator.AUTH_TYPE_BASIC))
                .isEqualTo(AuthResult.PASS);
        assertThat(authenticator.authenticate(header, "POST", URI,
                "hgw", "other", "hgw", "other", CpeAuthenticator.AUTH_TYPE_BASIC))
                .isEqualTo(AuthResult.FAIL);
    }

    /**
     * 无 qop 的 Digest 头（见实现注释：nonce 与 HA2 之间缺少冒号）。
     */
    private DigestAuth digest(String user, String password) {
        String ha1 = md5(user + ":" + REALM + ":" + password);
        String ha2 = md5("POST:" + URI);
        String response = md5(ha1 + ":" + NONCE + ha2);
        return new DigestAuth(user, response);
    }

    private record DigestAuth(String user, String response) {

        String header() {
            return "Digest username=\"" + user + "\", realm=\"" + REALM
                    + "\", nonce=\"" + NONCE + "\", uri=\"" + URI
                    + "\", response=\"" + response + "\"";
        }
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
