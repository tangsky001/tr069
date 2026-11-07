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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** OLT Telnet 长连接管理：每台设备最多 SESSIONS_PER_DEVICE 条并行常驻会话 */
@Slf4j
@Component
public class OltSessionPool {

    /** 每台设备并行会话上限 */
    private static final int SESSIONS_PER_DEVICE = 3;

    /** 不保活时连接空闲退出登录的时长 */
    private static final long IDLE_QUIT_MS = 60_000L;

    /** 保活探活默认周期 */
    private static final long DEFAULT_KEEPALIVE_INTERVAL_MS = 120_000L;

    /** 排队等待上限，超时报设备忙 */
    private static final long SESSION_WAIT_MS = 20_000L;

    /** 后台巡检节拍 */
    private static final long SWEEP_TICK_MS = 15_000L;

    /** 管理页可选的探活周期（分钟） */
    private static final List<Integer> KEEPALIVE_MINUTE_OPTIONS = List.of(1, 2, 5, 10);

    @Value("${acs.olt.connect-timeout-ms:8000}")
    private int connectTimeoutMs;

    @Value("${acs.olt.login-timeout-ms:15000}")
    private int loginTimeoutMs;

    @Value("${acs.olt.command-timeout-ms:20000}")
    private int commandTimeoutMs;

    /** 命令回显是否打进日志（排障时开，平时关） */
    @Value("${acs.olt.log-echo:false}")
    private boolean logEcho;

    /** 保活开关，管理页可切换 */
    private volatile boolean keepaliveEnabled = true;

    /** 保活探活周期(毫秒)，管理页可调 */
    private volatile long keepaliveIntervalMs = DEFAULT_KEEPALIVE_INTERVAL_MS;

    /** 按 host:port@localIp 分设备的通道 */
    private final ConcurrentHashMap<String, DeviceChannel> devices = new ConcurrentHashMap<>();

    private ScheduledExecutorService keeper;

    /** 在设备长连接上执行一段操作 */
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
        log.info("OLT 长连接管理已启动：每设备最多 {} 条并行会话，保活开关默认{}（{}分钟探活一次）",
                SESSIONS_PER_DEVICE, keepaliveEnabled ? "开" : "关", keepaliveIntervalMs / 60000);
    }

    @PreDestroy
    public void stop() {
        if (keeper != null) {
            keeper.shutdownNow();
        }
        for (DeviceChannel channel : devices.values()) {
            for (SessionSlot slot : channel.slots) {
                slot.executor.shutdownNow();
                if (slot.session != null) {
                    closeQuietly(slot.session);
                }
            }
        }
        devices.clear();
    }

    /** 在设备的常驻会话上执行任务：挑空闲会话跑，全忙才排队 */
    public <T> T execute(OltDevice device, Charset charset, boolean charsetStrict,
                         SessionTask<T> task) throws IOException {
        DeviceChannel channel = devices.computeIfAbsent(poolKey(device), DeviceChannel::new);
        SessionSlot slot = channel.pick();
        AtomicBoolean started = new AtomicBoolean(false);
        Future<T> future;
        try {
            future = slot.executor.submit(() -> {
                started.set(true);
                return runOn(channel, slot, device, charset, charsetStrict, task);
            });
        } catch (RejectedExecutionException e) {
            throw new OltTelnetException("OLT " + device.getHost() + " 的会话通道已关闭");
        }
        try {
            return future.get(SESSION_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (started.get()) {
                return awaitOwn(future, device);
            }
            future.cancel(false);
            if (channel.anySaving()) {
                throw new OltTelnetException("OLT " + device.getHost()
                        + " 正在保存配置（写 flash 期间不能执行其它操作），请稍后重试");
            }
            throw new OltTelnetException("OLT " + device.getHost()
                    + " 正在执行其它采集任务（长连接排队中），请稍后再试");
        } catch (InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw new OltTelnetException("等待 OLT " + device.getHost() + " 会话被中断");
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    /** 等自己的任务跑完 */
    private <T> T awaitOwn(Future<T> future, OltDevice device) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw new OltTelnetException("等待 OLT " + device.getHost() + " 会话被中断");
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    /** 还原执行线程里的异常 */
    private static IOException unwrap(ExecutionException e) throws IOException {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        if (cause instanceof IOException) {
            return (IOException) cause;
        }
        if (cause instanceof RuntimeException) {
            throw (RuntimeException) cause;
        }
        return new OltTelnetException("OLT 会话任务执行失败：" + cause.getMessage(), cause);
    }

    /** 在会话线程上跑任务：一条会话同一时刻只有一段收发 */
    private <T> T runOn(DeviceChannel channel, SessionSlot slot, OltDevice device, Charset charset,
                        boolean charsetStrict, SessionTask<T> task) throws IOException {
        OltCliSession session = slot.session;
        if (session != null && (!matches(slot, device, charset, charsetStrict) || !session.isAlive())) {
            closeQuietly(session);
            if (slot.session == session) {
                slot.session = null;
            }
            session = null;
        }
        if (session == null) {
            try {
                session = OltCliSession.open(device, charset, connectTimeoutMs, loginTimeoutMs,
                        commandTimeoutMs, logEcho);
            } catch (IOException e) {
                // 会话登录被设备拒绝时回退到已有会话排队
                SessionSlot fallback = channel == null ? null : channel.fallbackSlot(slot);
                if (fallback != null) {
                    log.debug("OLT 长连接：{} 的第 {} 条会话登录被拒({})，回退到会话 {} 排队",
                            channel.key, slot.index + 1, e.getMessage(), fallback.name);
                    return executeOn(fallback, device, charset, charsetStrict, task);
                }
                throw e;
            }
            slot.session = session;
            slot.charset = charset;
            slot.username = OltCliSession.usernameOf(device);
            slot.password = OltCliSession.passwordOf(device);
            slot.vendor = OltVendor.of(device.getVendor());
        }
        if (session.inSaveGrace()) {
            throw new OltTelnetException("OLT " + device.getHost()
                    + " 刚保存完配置，请等几秒再操作");
        }
        session.beginTask();
        try {
            return task.run(session);
        } finally {
            long now = System.currentTimeMillis();
            slot.lastUsedAt = now;
            slot.lastKeepaliveAt = now;
            OltCliSession current = session;
            if (!current.isAlive()) {
                closeQuietly(current);
                if (slot.session == current) {
                    slot.session = null;
                }
            } else if (slot.index != 0 && channel != null && channel.quiet(slot)) {
                // 辅助会话用完即退，只留 0 号会话按保活开关常驻
                logoutQuietly(current);
                if (slot.session == current) {
                    slot.session = null;
                }
            }
        }
    }

    /** 退出登录并关闭会话 */
    private static void logoutQuietly(OltCliSession session) {
        try {
            session.logoutAndClose();
        } catch (RuntimeException e) {
            log.debug("退出 OLT 会话失败(忽略): {}", e.getMessage());
        }
    }

    /** 把任务改投到已有活会话的槽位排队 */
    private <T> T executeOn(SessionSlot slot, OltDevice device, Charset charset, boolean charsetStrict,
                            SessionTask<T> task) throws IOException {
        Future<T> future;
        try {
            future = slot.executor.submit(() -> runOn(null, slot, device, charset, charsetStrict, task));
        } catch (RejectedExecutionException e) {
            throw new OltTelnetException("OLT " + device.getHost() + " 的会话通道已关闭");
        }
        try {
            return future.get();
        } catch (InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw new OltTelnetException("等待 OLT " + device.getHost() + " 会话被中断");
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    /** 复用前核对会话身份 */
    private static boolean matches(SessionSlot slot, OltDevice device, Charset charset, boolean charsetStrict) {
        if (charsetStrict && !slot.charset.equals(charset)) {
            return false;
        }
        return slot.username.equals(OltCliSession.usernameOf(device))
                && slot.password.equals(OltCliSession.passwordOf(device))
                && slot.vendor == OltVendor.of(device.getVendor());
    }

    /** 后台巡检：探活到期或空闲退出 */
    private void sweep() {
        boolean keepalive = keepaliveEnabled;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, DeviceChannel> entry : devices.entrySet()) {
            for (SessionSlot slot : entry.getValue().slots) {
                if (slot.session == null) {
                    continue;
                }
                // 只有 0 号会话按保活开关常驻，辅助会话一律空闲即退
                boolean primary = keepalive && slot.index == 0;
                boolean due = primary
                        ? now - slot.lastKeepaliveAt >= keepaliveIntervalMs
                        : now - slot.lastUsedAt >= IDLE_QUIT_MS;
                if (!due) {
                    continue;
                }
                try {
                    slot.executor.execute(() -> maintain(slot, primary));
                } catch (RejectedExecutionException ignored) {
                    // 通道已关闭
                }
            }
        }
    }

    /** 单条会话的维护：探活保活，或未开保活时退出空闲连接 */
    private void maintain(SessionSlot slot, boolean keepalive) {
        OltCliSession session = slot.session;
        if (session == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (keepalive) {
            if (now - slot.lastKeepaliveAt < keepaliveIntervalMs) {
                return;
            }
            if (session.isAlive() && session.ping(commandTimeoutMs)) {
                slot.lastKeepaliveAt = System.currentTimeMillis();
            } else {
                log.debug("OLT 长连接：{} 探活失败，关闭待下次重建", slot.name);
                closeQuietly(session);
                if (slot.session == session) {
                    slot.session = null;
                }
            }
        } else {
            if (now - slot.lastUsedAt < IDLE_QUIT_MS) {
                return;
            }
            log.debug("OLT 长连接（未开保活）：{} 空闲超过 1 分钟，quit 退出登录", slot.name);
            session.logoutAndClose();
            if (slot.session == session) {
                slot.session = null;
            }
        }
    }

    /** 连接管理状态，管理页展示用 */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("keepaliveEnabled", keepaliveEnabled);
        status.put("keepaliveIntervalMs", keepaliveIntervalMs);
        status.put("longConnection", true);
        status.put("idleQuitMs", IDLE_QUIT_MS);
        status.put("keepaliveMinutes", keepaliveMinutes());
        status.put("keepaliveMinuteOptions", KEEPALIVE_MINUTE_OPTIONS);
        status.put("idleQuitText", (IDLE_QUIT_MS / 60000) + " 分钟");
        Map<String, Boolean> connections = new LinkedHashMap<>();
        Map<String, Boolean> saving = new LinkedHashMap<>();
        devices.forEach((key, channel) -> {
            boolean alive = false;
            boolean savingNow = false;
            for (SessionSlot slot : channel.slots) {
                OltCliSession session = slot.session;
                if (session == null) {
                    continue;
                }
                alive |= session.isAlive();
                savingNow |= session.isSaving();
            }
            connections.put(key, alive);
            saving.put(key, savingNow);
        });
        status.put("deviceConnections", connections);
        status.put("deviceSaving", saving);
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

    /** 探活周期落到哪个整档（分钟） */
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

    /** 一台设备的通道：最多 SESSIONS_PER_DEVICE 条并行会话 */
    private static final class DeviceChannel {
        final String key;
        /** 会话槽位，连接按需建立 */
        final List<SessionSlot> slots;

        DeviceChannel(String key) {
            this.key = key;
            List<SessionSlot> list = new ArrayList<>(SESSIONS_PER_DEVICE);
            for (int i = 0; i < SESSIONS_PER_DEVICE; i++) {
                list.add(new SessionSlot(key + "#" + (i + 1), i));
            }
            this.slots = list;
        }

        /** 挑一条会话：没在跑任务的优先，其次活会话，队列最短的优先 */
        SessionSlot pick() {
            SessionSlot best = null;
            int bestScore = Integer.MAX_VALUE;
            for (SessionSlot slot : slots) {
                OltCliSession session = slot.session;
                boolean alive = session != null && session.isAlive();
                // 正在跑任务的槽位必须排最后：并发分段采集时几个任务要落到不同槽位，
                // 不然全挤在一条会话上串行，后排的任务还会排队超时被取消
                int score = (slot.executor.getActiveCount() > 0 ? 1_000_000 : 0)
                        + (alive ? 0 : 1_000)
                        + slot.executor.getQueue().size();
                if (score < bestScore) {
                    bestScore = score;
                    best = slot;
                }
            }
            return best;
        }

        /** 登录被拒时的退路：另一条活着的会话 */
        SessionSlot fallbackSlot(SessionSlot failed) {
            for (SessionSlot slot : slots) {
                if (slot != failed && slot.session != null && slot.session.isAlive()) {
                    return slot;
                }
            }
            return null;
        }

        boolean anySaving() {
            for (SessionSlot slot : slots) {
                OltCliSession session = slot.session;
                if (session != null && session.isSaving()) {
                    return true;
                }
            }
            return false;
        }

        /** 除指定槽位外全部空闲 */
        boolean quiet(SessionSlot except) {
            for (SessionSlot slot : slots) {
                if (slot == except) {
                    continue;
                }
                if (slot.executor.getActiveCount() > 0 || !slot.executor.getQueue().isEmpty()) {
                    return false;
                }
            }
            return except.executor.getQueue().isEmpty();
        }
    }

    /** 一条常驻会话：单线程执行器 + 登录态连接 */
    private static final class SessionSlot {
        final String name;
        /** 槽位编号 */
        final int index;
        /** 单线程执行器，串行化收发 */
        final ThreadPoolExecutor executor;
        /** 常驻连接，断了或身份变了才重建 */
        volatile OltCliSession session;
        volatile long lastUsedAt;
        volatile long lastKeepaliveAt;
        /** 当前连接的身份快照 */
        Charset charset;
        String username;
        String password;
        OltVendor vendor;

        SessionSlot(String name, int index) {
            this.name = name;
            this.index = index;
            ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1,
                    60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                    runnable -> {
                        Thread thread = new Thread(runnable, "olt-cli-" + name);
                        thread.setDaemon(true);
                        return thread;
                    });
            pool.allowCoreThreadTimeOut(true);
            this.executor = pool;
        }
    }
}
