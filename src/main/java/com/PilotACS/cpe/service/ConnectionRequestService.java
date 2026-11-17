/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.cpe.service;

import com.PilotACS.cpe.config.CpeProperties;
import com.PilotACS.cpe.session.SessionRegistry;
import com.PilotACS.common.log.Logs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ACS → CPE 反向连接：
 * GET 设备的 ConnectionRequestURL，Digest（回退 Basic）认证，失败带退避重试。
 */
@Service
@RequiredArgsConstructor
public class ConnectionRequestService {

    private final CpeProperties props;
    private final SessionRegistry registry;

    private static final int MAX_KICKS = 10;

    private final ScheduledExecutorService retryPool =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cr-retry");
                t.setDaemon(true);
                return t;
            });

    private final Map<String, ScheduledFuture<?>> pendingKicks = new ConcurrentHashMap<>();

    /** 唤醒（用配置里的默认账号） */
    public boolean wake(String url) {
        return wake(url, null, null);
    }

    /**
     * 唤醒设备。只允许两类凭据：
     * 1. 配置文件 acs.cpe.cr-username/cr-password
     * 2. ACS 首次下发到设备的同一组账密（即上面那组；下发前设备被旧平台改过密码时 401 属正常）
     * 设备树上报的 ConnectionRequestUsername（如 RMS）密码未知，不再参与猜配。
     * username/password 入参保留仅为兼容旧调用，非空且与配置不同时作为最后兜底试一次。
     */
    public boolean wake(String url, String username, String password) {
        if (url == null || url.isBlank()) {
            return false;
        }
        List<String[]> pairs = new ArrayList<>();
        pairs.add(new String[]{props.getCrUsername(), props.getCrPassword()});
        boolean foreignCred = username != null && !username.isBlank()
                && !username.equals(props.getCrUsername());
        if (foreignCred && password != null && !password.isBlank()) {
            pairs.add(new String[]{username, password});
        }

        String lastAuth = null;
        int lastCode = 0;
        for (String[] cred : pairs) {
            try {
                HttpURLConnection conn = open(url);
                int code = conn.getResponseCode();
                if (code == 200 || code == 204) {
                    conn.disconnect();
                    return true;
                }
                String auth = conn.getHeaderField("WWW-Authenticate");
                conn.disconnect();
                if (auth == null || code != 401) {
                    Logs.warn(Logs.Module.CPE, "反向连接被拒", "url", maskUrl(url), "状态码", code);
                    return false;
                }
                // 第二次带认证：Digest 用设备挑战头现场算，Basic 直接 base64
                HttpURLConnection conn2 = open(url);
                conn2.setRequestProperty("Authorization", authorization(auth, url, cred[0], cred[1]));
                int code2 = conn2.getResponseCode();
                conn2.disconnect();
                if (code2 == 200 || code2 == 204) {
                    Logs.info(Logs.Module.CPE, "反向连接认证成功", "url", maskUrl(url), "账号", cred[0],
                            "认证方式", auth.startsWith("Digest") ? "Digest" : "Basic");
                    return true;
                }
                lastCode = code2;
                lastAuth = auth;
            } catch (Exception e) {
                Logs.warn(Logs.Module.CPE, "反向连接失败", "url", maskUrl(url), "原因", e.getMessage());
                return false;
            }
        }
        Logs.warn(Logs.Module.CPE, "反向连接被拒", "url", maskUrl(url), "状态码", lastCode,
                "认证方式", lastAuth == null ? "?" : (lastAuth.startsWith("Digest") ? "Digest" : "Basic"),
                "尝试账号", pairs.stream().map(p -> p[0]).distinct().toList(),
                foreignCred ? "提示" : null,
                foreignCred ? "设备侧 CR 账密被外部平台改过，请重新下发 ConnectionRequestUsername/Password" : null);
        return false;
    }

    /** 唤醒并带重试：立即一次，失败 5s 后再踢、之后每 30s，最多 10 次；出现活会话即停 */
    public void wakeWithRetry(String sn, String url, String username, String password) {
        if (url == null || url.isBlank()) {
            return;
        }
        ScheduledFuture<?> old = pendingKicks.remove(sn);
        if (old != null) {
            old.cancel(false);
        }
        kick(sn, url, username, password, 0, 0);
    }

    private void kick(String sn, String url, String username, String password, int round, long delayMs) {
        ScheduledFuture<?> f = retryPool.schedule(() -> {
            try {
                if (registry.bySn(sn) != null) {
                    Logs.info(Logs.Module.CPE, "设备已有活会话，停止反向连接", "sn", sn, "已踢", round + 1);
                    return;
                }
                if (wake(url, username, password)) {
                    Logs.info(Logs.Module.CPE, "反向连接成功", "sn", sn, "第几次", round + 1);
                    return;
                }
                if (round + 1 >= MAX_KICKS) {
                    Logs.warn(Logs.Module.CPE, "反向连接重试放弃", "sn", sn, "次数", MAX_KICKS);
                    return;
                }
                kick(sn, url, username, password, round + 1, round == 0 ? 5_000L : 30_000L);
            } catch (Exception e) {
                Logs.warn(Logs.Module.CPE, "反向连接重试异常", "sn", sn, "原因", e.getMessage());
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        pendingKicks.put(sn, f);
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(props.getCrTimeoutMs());
        conn.setReadTimeout(props.getCrTimeoutMs());
        return conn;
    }

    private String authorization(String challenge, String url, String username, String password) {
        if (challenge.startsWith("Digest")) {
            return digest(challenge, url, username, password);
        }
        // Basic 回退
        String up = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(up.getBytes(StandardCharsets.UTF_8));
    }

    private String digest(String challenge, String url, String username, String password) {
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
        // Digest 的 uri 必须与请求行一致：带 query 的 CR URL 要连 ?sn=xxx 一起算，否则响应值对不上
        URI u = URI.create(url);
        String uri = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        if (u.getRawQuery() != null && !u.getRawQuery().isEmpty()) {
            uri += "?" + u.getRawQuery();
        }

        String ha1 = md5(username + ":" + realm + ":" + password);
        String ha2 = md5("GET:" + uri);
        String response;
        String sbAuth = "Digest username=\"" + username + "\", realm=\"" + realm
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
