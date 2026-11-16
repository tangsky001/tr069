/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.service;

import com.PilotACS.cpe.config.CpeProperties;
import com.PilotACS.common.log.Logs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ACS → CPE 反向连接：
 * GET 设备的 ConnectionRequestURL，Digest（回退 Basic）认证。
 */
@Service
@RequiredArgsConstructor
public class ConnectionRequestService {

    private final CpeProperties props;

    /** 唤醒设备，返回是否成功拿到 200/204 */
    public boolean wake(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            HttpURLConnection conn = open(url);
            int code = conn.getResponseCode();
            if (code == 200 || code == 204) {
                return true;
            }
            String auth = conn.getHeaderField("WWW-Authenticate");
            conn.disconnect();
            if (auth == null || code != 401) {
                return false;
            }
            // 第二次带认证
            HttpURLConnection conn2 = open(url);
            conn2.setRequestProperty("Authorization", authorization(auth, url));
            int code2 = conn2.getResponseCode();
            conn2.disconnect();
            if (code2 == 200 || code2 == 204) {
                return true;
            }
            Logs.warn(Logs.Module.CPE, "反向连接被拒", "url", maskUrl(url), "状态码", code2);
            return false;
        } catch (Exception e) {
            Logs.warn(Logs.Module.CPE, "反向连接失败", "url", maskUrl(url), "原因", e.getMessage());
            return false;
        }
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(props.getCrTimeoutMs());
        conn.setReadTimeout(props.getCrTimeoutMs());
        return conn;
    }

    private String authorization(String challenge, String url) {
        if (challenge.startsWith("Digest")) {
            return digest(challenge, url);
        }
        // Basic 回退
        String up = props.getCrUsername() + ":" + props.getCrPassword();
        return "Basic " + Base64.getEncoder().encodeToString(up.getBytes(StandardCharsets.UTF_8));
    }

    private String digest(String challenge, String url) {
        Map<String, String> p = new java.util.HashMap<>();
        Matcher m = Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]+))").matcher(challenge);
        while (m.find()) {
            p.put(m.group(1), m.group(2) != null ? m.group(2) : m.group(3));
        }
        String realm = p.getOrDefault("realm", "");
        String nonce = p.getOrDefault("nonce", "");
        String qop = p.getOrDefault("qop", "");
        String cnonce = HexFormat.of().formatHex(randBytes());
        String nc = "00000001";
        String uri = URI.create(url).getPath() == null ? "/" : URI.create(url).getPath();

        String ha1 = md5(props.getCrUsername() + ":" + realm + ":" + props.getCrPassword());
        String ha2 = md5("GET:" + uri);
        String response;
        String sbAuth = "Digest username=\"" + props.getCrUsername() + "\", realm=\"" + realm
                + "\", nonce=\"" + nonce + "\", uri=\"" + uri + "\", response=\"";
        if (qop != null && !qop.isBlank()) {
            response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
            sbAuth += response + "\", qop=auth, nc=" + nc + ", cnonce=\"" + cnonce + "\"";
        } else {
            response = md5(ha1 + ":" + nonce + ":" + ha2);
            sbAuth += response + "\"";
        }
        if (p.containsKey("opaque")) {
            sbAuth += ", opaque=\"" + p.get("opaque") + "\"";
        }
        return sbAuth;
    }

    private byte[] randBytes() {
        byte[] b = new byte[8];
        new java.security.SecureRandom().nextBytes(b);
        return b;
    }

    private String md5(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private String maskUrl(String url) {
        return url.replaceAll("(http://)[^/@]*@", "$1***@");
    }
}
