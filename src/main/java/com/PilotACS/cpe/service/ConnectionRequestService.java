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

/** ACS → CPE 反向连接： */
@Service
@RequiredArgsConstructor
public class ConnectionRequestService {

    private final CpeProperties props;
    private final SessionRegistry registry;

    private static final int MAX_KICKS = 10;
    /** 踢成功后等待设备 Inform 的归因窗口：窗口内 Inform 视为 CR 唤醒触发 */
    private static final long KICK_INFORM_WINDOW_MS = 60_000L;

    private final ScheduledExecutorService retryPool =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cr-retry");
                t.setDaemon(true);
                return t;
            });

    /** 一条重试链：chain 是代际令牌，wakeWithRetry 换链后旧链任务体自检退出 */
    private record Kick(long chain, ScheduledFuture<?> head) {
    }

    private final Map<String, Kick> pendingKicks = new ConcurrentHashMap<>();

    /** 唤醒（用配置里的默认账号） */
    public boolean wake(String url) {
        return wake(url, null, null);
    }

    /** 唤醒设备。只允许两类凭据： */
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
                // 网络级异常不终止多凭据尝试：继续试下一组凭据，全失败后才判负
                lastCode = 0;
                lastAuth = "error: " + e.getMessage();
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
        if (sn == null || sn.isBlank() || url == null || url.isBlank()) {
            return;
        }
        // compute 原子完成"取消旧重试链 + 换新链"：并发调用（sweep 回队反踢 + REST 建任务反踢）
        pendingKicks.compute(sn, (k, old) -> {
            if (old != null) {
                old.head().cancel(false);
            }
            long chain = System.nanoTime();
            return new Kick(chain, scheduleKick(sn, url, username, password, 0, 0, chain));
        });
    }

    private ScheduledFuture<?> scheduleKick(String sn, String url, String username, String password,
                                            int round, long delayMs, long chain) {
        return retryPool.schedule(() -> {
            // 代际自检：链已被新调用替换（cancel 只能取消队头，续跑轮次靠这里退出），直接放弃
            Kick cur = pendingKicks.get(sn);
            if (cur == null || cur.chain() != chain) {
                return;
            }
            try {
                if (registry.bySn(sn) != null) {
                    Logs.info(Logs.Module.CPE, "设备已有活会话，停止反向连接", "sn", sn, "已踢", round + 1);
                    pendingKicks.remove(sn, cur);
                    return;
                }
                if (wake(url, username, password)) {
                    Logs.info(Logs.Module.CPE, "反向连接成功", "sn", sn, "第几次", round + 1);
                    // 挂待 Inform 标记：窗口内设备 Inform 上来即可归因为本次唤醒触发
                    registry.markKickPending(sn, KICK_INFORM_WINDOW_MS);
                    pendingKicks.remove(sn, cur);
                    return;
                }
                if (round + 1 >= MAX_KICKS) {
                    Logs.warn(Logs.Module.CPE, "反向连接重试放弃", "sn", sn, "次数", MAX_KICKS);
                    pendingKicks.remove(sn, cur);
                    return;
                }
                scheduleKick(sn, url, username, password, round + 1,
                        round == 0 ? 5_000L : 30_000L, chain);
            } catch (Exception e) {
                // 异常只终止本链并摘除登记，不盲目重排（避免异常风暴）；下次唤醒会建新链
                Logs.warn(Logs.Module.CPE, "反向连接重试异常，本链终止", "sn", sn,
                        "轮次", round + 1, "原因", e.getMessage());
                pendingKicks.remove(sn, cur);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
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

        // 算法支持：MD5 / MD5-sess / SHA-256 / SHA-256-sess（RFC 7616）。
        String algo = p.getOrDefault("algorithm", "MD5").toUpperCase();
        boolean sha256 = algo.startsWith("SHA-256");
        boolean sess = algo.endsWith("-SESS");
        if (!algo.equals("MD5") && !sha256) {
            throw new IllegalArgumentException("不支持的 Digest 算法: " + p.get("algorithm"));
        }
        String hashAlg = sha256 ? "SHA-256" : "MD5";
        String ha1 = hash(hashAlg, username + ":" + realm + ":" + password);
        if (sess) {
            ha1 = hash(hashAlg, ha1 + ":" + nonce + ":" + cnonce);
        }
        String ha2 = hash(hashAlg, "GET:" + uri);
        String response;
        String sbAuth = "Digest username=\"" + username + "\", realm=\"" + realm
                + "\", nonce=\"" + nonce + "\", uri=\"" + uri + "\", algorithm=" + algo + ", response=\"";
        if (qop != null && !qop.isBlank()) {
            response = hash(hashAlg, ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
            sbAuth += response + "\", qop=auth, nc=" + nc + ", cnonce=\"" + cnonce + "\"";
        } else {
            response = hash(hashAlg, ha1 + ":" + nonce + ":" + ha2);
            sbAuth += response + "\"";
            if (sess) {
                // -sess 派生即使无 qop 也依赖 cnonce，必须回传
                sbAuth += ", cnonce=\"" + cnonce + "\", nc=" + nc;
            }
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

    private String hash(String algorithm, String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance(algorithm).digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private String maskUrl(String url) {
        return url.replaceAll("(http://)[^/@]*@", "$1***@");
    }
}
