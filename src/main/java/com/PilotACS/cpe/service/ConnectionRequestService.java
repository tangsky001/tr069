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

/** ACS → CPE 反向连接 */
@Service
@RequiredArgsConstructor
public class ConnectionRequestService {

    private final CpeProperties props;
    private final SessionRegistry registry;

    private static final int MAX_KICKS = 10;
    /** 踢成功后等待设备 Inform 的归因窗口 */
    private static final long KICK_INFORM_WINDOW_MS = 60_000L;
    /** HTTP 200 后等设备真正上报的校验窗口；超时视为"CR 送达但设备没醒" */
    private static final long KICK_CONFIRM_MS = 8_000L;

    /** CR 唤醒调度池：多台离线设备并行反踢（单线程时一台设备退避链可拖死所有唤醒），大小 acs.cpe.cr-threads */
    private ScheduledExecutorService retryPool;

    @jakarta.annotation.PostConstruct
    void initRetryPool() {
        retryPool = Executors.newScheduledThreadPool(Math.max(1, props.getCrThreads()),
                Thread.ofVirtual().name("cr-retry-", 0).factory());
    }

    /** 一条重试链 */
    private record Kick(long chain, ScheduledFuture<?> head) {
    }

    private final Map<String, Kick> pendingKicks = new ConcurrentHashMap<>();

    /** 唤醒（用配置里的默认账号） */
    public boolean wake(String url) {
        return wake(url, null, null);
    }

    /** 唤醒设备（支持指定凭据） */
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
                    Logs.warn(Logs.Module.CPE_PUSH, "反向连接被拒", "url", maskUrl(url), "状态码", code);
                    return false;
                }
                HttpURLConnection conn2 = open(url);
                conn2.setRequestProperty("Authorization", authorization(auth, url, cred[0], cred[1]));
                int code2 = conn2.getResponseCode();
                conn2.disconnect();
                if (code2 == 200 || code2 == 204) {
                    Logs.info(Logs.Module.CPE_PUSH, "反向连接认证成功", "url", maskUrl(url), "账号", cred[0],
                            "认证方式", auth.startsWith("Digest") ? "Digest" : "Basic");
                    return true;
                }
                lastCode = code2;
                lastAuth = auth;
            } catch (Exception e) {
                lastCode = 0;
                lastAuth = "error: " + e.getMessage();
            }
        }
        boolean netError = lastAuth != null && lastAuth.startsWith("error:");
        Logs.warn(Logs.Module.CPE_PUSH, netError ? "反向连接失败（未收到 HTTP 响应）" : "反向连接被拒",
                "url", maskUrl(url), "状态码", lastCode,
                "认证方式", netError || lastAuth == null ? "未到达认证环节"
                        : (lastAuth.startsWith("Digest") ? "Digest" : "Basic"),
                "尝试账号", pairs.stream().map(p -> p[0]).distinct().toList(),
                netError ? "网络原因" : null, netError ? lastAuth : null,
                foreignCred ? "提示" : null,
                foreignCred ? "设备侧 CR 账密被外部平台改过，请重新下发 ConnectionRequestUsername/Password" : null);
        return false;
    }

    /** 唤醒并带重试（出现活会话即停） */
    public void wakeWithRetry(String sn, String url, String username, String password) {
        if (sn == null || sn.isBlank() || url == null || url.isBlank()) {
            return;
        }
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
            Kick cur = pendingKicks.get(sn);
            if (cur == null || cur.chain() != chain) {
                return;
            }
            try {
                if (registry.bySn(sn) != null) {
                    Logs.info(Logs.Module.CPE_PUSH, "设备已有活会话，停止反向连接", "sn", sn, "已踢", round + 1);
                    pendingKicks.remove(sn, cur);
                    return;
                }
                long kickedAt = System.currentTimeMillis();
                if (wake(url, username, password)) {
                    // HTTP 200 只代表 CR 请求被设备 HTTP 服务受理，不代表设备会发起 Inform
                    // （同 IP/端口多设备串台、设备忽略 CR 时都只会 200 不上报）。
                    // 先挂标记，8 秒后校验是否真的有该 SN 的 Inform；没来就继续重试并告警
                    Logs.info(Logs.Module.CPE_PUSH, "反向连接请求已送达，等待设备上报确认",
                            "sn", sn, "第几次", round + 1);
                    registry.markKickPending(sn, KICK_INFORM_WINDOW_MS);
                    scheduleConfirm(sn, url, username, password, round, chain, cur, kickedAt);
                    return;
                }
                if (round + 1 >= MAX_KICKS) {
                    Logs.warn(Logs.Module.CPE_PUSH, "反向连接重试放弃", "sn", sn, "次数", MAX_KICKS);
                    pendingKicks.remove(sn, cur);
                    return;
                }
                scheduleKick(sn, url, username, password, round + 1,
                        round == 0 ? 5_000L : 30_000L, chain);
            } catch (Exception e) {
                Logs.warn(Logs.Module.CPE_PUSH, "反向连接重试异常，本链终止", "sn", sn,
                        "轮次", round + 1, "原因", e.getMessage());
                pendingKicks.remove(sn, cur);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** 校验 CR 是否真的唤醒了目标设备：只认该 SN 在踢之后的新 Inform，周期上报不算命中 */
    private void scheduleConfirm(String sn, String url, String username, String password,
                                 int round, long chain, Kick cur, long kickedAt) {
        retryPool.schedule(() -> {
            Kick now = pendingKicks.get(sn);
            if (now == null || now.chain() != chain) {
                return;
            }
            if (registry.lastInformAt(sn) >= kickedAt - 1_000L) {
                Logs.info(Logs.Module.CPE_PUSH, "反向连接命中：设备已上报 Inform（排队任务已装载）", "sn", sn,
                        "第几次", round + 1);
                pendingKicks.remove(sn, cur);
                return;
            }
            // HTTP 200 但设备没醒：明确告警（串台/CR 地址不可达），继续重试而不是假成功
            Logs.warn(Logs.Module.CPE_PUSH,
                    "CR 请求已送达但设备未上报（疑似 CR 地址串台/设备忽略 CR），继续重试",
                    "sn", sn, "第几次", round + 1, "校验窗口ms", KICK_CONFIRM_MS);
            if (round + 1 >= MAX_KICKS) {
                Logs.warn(Logs.Module.CPE_PUSH, "反向连接命中校验重试放弃", "sn", sn,
                        "次数", MAX_KICKS, "建议", "检查 CR URL 端口映射/两设备是否共用地址");
                pendingKicks.remove(sn, cur);
                return;
            }
            scheduleKick(sn, url, username, password, round + 1, 5_000L, chain);
        }, KICK_CONFIRM_MS, TimeUnit.MILLISECONDS);
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
        URI u = URI.create(url);
        String uri = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        if (u.getRawQuery() != null && !u.getRawQuery().isEmpty()) {
            uri += "?" + u.getRawQuery();
        }

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
