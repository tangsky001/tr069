package com.tr069.acs.olt.service;

import com.tr069.acs.olt.entity.OltDevice;
import com.tr069.acs.olt.telnet.OltCliSession;
import com.tr069.acs.olt.telnet.OltTelnetException;
import com.tr069.acs.olt.telnet.OltVendor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** OLT Telnet 会话池：每台设备固定 2 个会话轮换，行为由管理页的「保活开关」决定 */
@Slf4j
@Component
public class OltSessionPool {

    /** 每台 OLT 的会话数（轮换使用），也就是 OLT 侧最多占用的登录名额数 */
    private static final int SESSIONS_PER_DEVICE = 2;

    /** 不保活时：会话空闲超过 1 分钟就 quit 退出登录 */
    private static final long IDLE_QUIT_MS = 60_000L;

    /** 保活探活的默认周期：2 分钟 */
    private static final long DEFAULT_KEEPALIVE_INTERVAL_MS = 120_000L;

    /** 借会话的最长排队时间：超过就报「设备忙」，不让 HTTP 请求无限挂着 */
    private static final long SESSION_WAIT_MS = 20_000L;

    /** 后台巡检节拍：探活到期与否、不保活模式的 1 分钟退出都按这个粒度检查 */
    private static final long SWEEP_TICK_MS = 15_000L;

    /** 管理页可选的探活周期（分钟）：现场只按整档调，页面下拉照这份清单渲染 */
    private static final List<Integer> KEEPALIVE_MINUTE_OPTIONS = List.of(1, 2, 5, 10);

    @Value("${acs.olt.connect-timeout-ms:8000}")
    private int connectTimeoutMs;

    @Value("${acs.olt.login-timeout-ms:15000}")
    private int loginTimeoutMs;

    @Value("${acs.olt.command-timeout-ms:20000}")
    private int commandTimeoutMs;

    /** 管理页的保活开关（运行时，不进配置文件），默认开：会话常驻登录态，采集不再反复登录 */
    private volatile boolean keepaliveEnabled = true;

    /** 保活探活周期(毫秒)，管理页可调 */
    private volatile long keepaliveIntervalMs = DEFAULT_KEEPALIVE_INTERVAL_MS;

    /** 按 host:port 分设备的池。空池对象不删：一个空池就一个信号量，量级是台账设备数 */
    private final ConcurrentHashMap<String, DevicePool> pools = new ConcurrentHashMap<>();

    private ScheduledExecutorService keeper;

    /** 在借到的会话上执行一段操作，结束后会话归还池中（死了或满了就关掉） */
    @FunctionalInterface
    public interface SessionTask<T> {
        T run(OltCliSession session) throws IOException;
    }

    @PostConstruct
    public void start() {
        keeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "olt-session-keeper");
            thread.setDaemon(true);
            return thread;
        });
        keeper.scheduleWithFixedDelay(this::sweep, SWEEP_TICK_MS, SWEEP_TICK_MS, TimeUnit.MILLISECONDS);
        log.info("OLT 会话池已启动：每设备 {} 个会话轮换，保活开关默认{}（{}分钟探活一次）",
                SESSIONS_PER_DEVICE, keepaliveEnabled ? "开" : "关", keepaliveIntervalMs / 60000);
    }

    @PreDestroy
    public void stop() {
        if (keeper != null) {
            keeper.shutdownNow();
        }
        for (Map.Entry<String, DevicePool> entry : pools.entrySet()) {
            synchronized (entry.getValue()) {
                entry.getValue().idle.forEach(pooled -> pooled.session.logoutAndClose());
                entry.getValue().idle.clear();
            }
        }
        pools.clear();
    }

    /** 借一个会话执行任务：优先复用空闲会话（省掉登录 + 厂商识别 + 字符集探测） */
    public <T> T execute(OltDevice device, Charset charset, boolean charsetStrict,
                         SessionTask<T> task) throws IOException {
        DevicePool pool = pools.computeIfAbsent(poolKey(device), key -> new DevicePool(SESSIONS_PER_DEVICE));
        boolean acquired;
        try {
            // 两个会话都在忙时排队等：限时等待而不是无限等——后台补采一轮可能跑一两分钟，
            // 无限等会让页面上的按需查询挂到前端超时还不知道原因，排队太久就直接告诉调用方设备忙
            acquired = pool.permits.tryAcquire(SESSION_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OltTelnetException("等待 OLT " + device.getHost() + " 会话被中断");
        }
        if (!acquired) {
            throw new OltTelnetException("OLT " + device.getHost()
                    + " 的会话都在忙（后台采集进行中），请稍后再试");
        }
        try {
            Pooled pooled = takeIdle(pool, device, charset, charsetStrict);
            OltCliSession session = pooled == null
                    ? OltCliSession.open(device, charset, connectTimeoutMs, loginTimeoutMs, commandTimeoutMs)
                    : pooled.session;
            try {
                // 会话锁内执行任务：同一条 TCP 连接同一时刻只跑一段「发命令 + 收回显」，
                // 两个线程哪怕拿到同一条会话（绕过信号量等意外情况），命令也不会交错串台
                session.ioLock().lock();
                try {
                    return task.run(session);
                } finally {
                    session.ioLock().unlock();
                }
            } finally {
                // 归还前退回基础视图并清上一轮回显；操作抛异常（如进不了 config 视图）
                // 不代表连接坏了，只要体检通过照样复用
                if (idleCount(pool) < SESSIONS_PER_DEVICE && session.reviveForPool(commandTimeoutMs)) {
                    offerIdle(pool, session, device, charset);
                } else {
                    closeQuietly(session);
                }
            }
        } finally {
            pool.permits.release();
        }
    }

    /** 取一个可复用的空闲会话：账号/字符集/厂商对得上、体检通过；取不到返回 null */
    private Pooled takeIdle(DevicePool pool, OltDevice device, Charset charset, boolean charsetStrict) {
        synchronized (pool) {
            while (!pool.idle.isEmpty()) {
                Pooled pooled = pool.idle.pollFirst();
                if (!matches(pooled, device, charset, charsetStrict)
                        || !pooled.session.reviveForPool(commandTimeoutMs)) {
                    // 死会话：关掉，下一位（或者由调用方重新登录建立）
                    closeQuietly(pooled.session);
                    continue;
                }
                pooled.lastUsedAt = System.currentTimeMillis();
                return pooled;
            }
        }
        return null;
    }

    /** 归还空闲会话。锁内再查一次数量：归还和保活/巡检并发时也不超上限 */
    private void offerIdle(DevicePool pool, OltCliSession session, OltDevice device, Charset charset) {
        synchronized (pool) {
            if (pool.idle.size() >= SESSIONS_PER_DEVICE) {
                closeQuietly(session);
                return;
            }
            pool.idle.addLast(new Pooled(session, charset,
                    OltCliSession.usernameOf(device), OltCliSession.passwordOf(device),
                    OltVendor.of(device.getVendor())));
        }
    }

    /** 复用前核对身份：账号、密码、厂商、字符集都对得上才复用，对不上就丢弃重连 */
    private static boolean matches(Pooled pooled, OltDevice device, Charset charset, boolean charsetStrict) {
        if (charsetStrict && !pooled.charset.equals(charset)) {
            return false;
        }
        return pooled.username.equals(OltCliSession.usernameOf(device))
                && pooled.password.equals(OltCliSession.passwordOf(device))
                && pooled.vendor == OltVendor.of(device.getVendor());
    }

    /** 后台巡检（15 秒一拍，按开关分两种行为） */
    private void sweep() {
        boolean keepalive = keepaliveEnabled;
        for (Map.Entry<String, DevicePool> entry : pools.entrySet()) {
            DevicePool pool = entry.getValue();
            synchronized (pool) {
                int size = pool.idle.size();
                for (int i = 0; i < size; i++) {
                    Pooled pooled = pool.idle.pollFirst();
                    boolean keep;
                    if (keepalive) {
                        // 没到探活周期就原样放回，不白发命令
                        keep = System.currentTimeMillis() - pooled.lastKeepaliveAt < keepaliveIntervalMs
                                || pooled.session.reviveForPool(commandTimeoutMs);
                        pooled.lastKeepaliveAt = System.currentTimeMillis();
                    } else {
                        keep = System.currentTimeMillis() - pooled.lastUsedAt < IDLE_QUIT_MS;
                        if (!keep) {
                            log.debug("OLT 会话池（未开保活）：{} 空闲超过 1 分钟，quit 退出登录", entry.getKey());
                            pooled.session.logoutAndClose();
                            continue;
                        }
                    }
                    if (keep) {
                        pool.idle.addLast(pooled);
                    } else {
                        log.debug("OLT 会话池：{} 空闲会话探活失败，关闭", entry.getKey());
                        closeQuietly(pooled.session);
                    }
                }
            }
        }
    }

    /** 会话池状态：保活开关、探活周期、每台设备的空闲会话数（管理页展示用） */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("keepaliveEnabled", keepaliveEnabled);
        status.put("keepaliveIntervalMs", keepaliveIntervalMs);
        status.put("sessionsPerDevice", SESSIONS_PER_DEVICE);
        status.put("idleQuitMs", IDLE_QUIT_MS);
        // 管理页下拉只认固定几档：周期若不是整档（比如后端配了 3 分钟），就近落一档显示，
        // 免得下拉框空着；换算与落档都在这里做，页面不再自己算分钟
        status.put("keepaliveMinutes", keepaliveMinutes());
        status.put("keepaliveMinuteOptions", KEEPALIVE_MINUTE_OPTIONS);
        // 「没开保活时会话闲多久退出」的文案：秒数换成分钟，页面提示语直接用
        status.put("idleQuitText", (IDLE_QUIT_MS / 60000) + " 分钟");
        Map<String, Integer> idleSessions = new LinkedHashMap<>();
        pools.forEach((key, pool) -> {
            int idle = idleCount(pool);
            if (idle > 0) {
                idleSessions.put(key, idle);
            }
        });
        status.put("idleSessions", idleSessions);
        return status;
    }

    /** 管理页的保活开关 */
    public Map<String, Object> setKeepalive(boolean enabled, Long intervalMs) {
        if (intervalMs != null) {
            if (intervalMs < 10_000L || intervalMs > 3_600_000L) {
                throw new IllegalArgumentException("保活周期须在 10 秒到 1 小时之间");
            }
            this.keepaliveIntervalMs = intervalMs;
        }
        this.keepaliveEnabled = enabled;
        log.info("OLT 会话保活已{}：{}分钟探活一次", enabled ? "开启" : "关闭", keepaliveIntervalMs / 60000);
        return status();
    }

    /** 当前探活周期落到哪个整档（分钟）：取的分钟数离哪档近就是哪档，一样近取小档 */
    private int keepaliveMinutes() {
        int minutes = Math.max(1, Math.round(keepaliveIntervalMs / 60000.0f));
        int best = KEEPALIVE_MINUTE_OPTIONS.get(0);
        for (int option : KEEPALIVE_MINUTE_OPTIONS) {
            if (Math.abs(option - minutes) < Math.abs(best - minutes)) {
                best = option;
            }
        }
        return best;
    }

    private static int idleCount(DevicePool pool) {
        synchronized (pool) {
            return pool.idle.size();
        }
    }

    private static String poolKey(OltDevice device) {
        int port = device.getPort() == null || device.getPort() <= 0 ? 23 : device.getPort();
        String localIp = device.getBindLocalIp();
        return device.getHost() + ":" + port
                + (localIp == null || localIp.isBlank() ? "" : "@" + localIp.trim());
    }

    private static void closeQuietly(OltCliSession session) {
        try {
            session.close();
        } catch (RuntimeException e) {
            log.debug("关闭 OLT 会话失败(忽略): {}", e.getMessage());
        }
    }

    /** 一台设备独享的池：并发名额（会话借出互斥）+ 空闲会话队列，设备之间不共用 */
    private static final class DevicePool {
        final Semaphore permits;
        final Deque<Pooled> idle = new ArrayDeque<>();

        DevicePool(int sessions) {
            this.permits = new Semaphore(sessions);
        }
    }

    /** 池中的一条会话：会话本体 + 借出匹配用的身份信息 + 巡检时间戳 */
    private static final class Pooled {
        final OltCliSession session;
        final Charset charset;
        final String username;
        final String password;
        final OltVendor vendor;
        volatile long lastUsedAt;
        volatile long lastKeepaliveAt;

        Pooled(OltCliSession session, Charset charset, String username, String password, OltVendor vendor) {
            this.session = session;
            this.charset = charset;
            this.username = username;
            this.password = password;
            this.vendor = vendor;
            long now = System.currentTimeMillis();
            this.lastUsedAt = now;
            this.lastKeepaliveAt = now;
        }
    }
}
