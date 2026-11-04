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

/** OLT Telnet 长连接管理：每台设备只有一条常驻连接，第一次登录后一直复用，任务在连接上排队执行 */
@Slf4j
@Component
public class OltSessionPool {

    /** 不保活时：连接空闲超过 1 分钟就 quit 退出登录 */
    private static final long IDLE_QUIT_MS = 60_000L;

    /** 保活探活的默认周期：2 分钟 */
    private static final long DEFAULT_KEEPALIVE_INTERVAL_MS = 120_000L;

    /** 排队等待上限：前面已有任务在跑时，最多等这么久，超时就报「设备忙」 */
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

    /** 管理页的保活开关（运行时，不进配置文件），默认开：长连接常驻登录态，采集不再反复登录 */
    private volatile boolean keepaliveEnabled = true;

    /** 保活探活周期(毫秒)，管理页可调 */
    private volatile long keepaliveIntervalMs = DEFAULT_KEEPALIVE_INTERVAL_MS;

    /** 按 host:port@localIp 分设备的通道。空通道不删：一个空通道就一个空闲线程槽，量级是台账设备数 */
    private final ConcurrentHashMap<String, DeviceChannel> devices = new ConcurrentHashMap<>();

    private ScheduledExecutorService keeper;

    /** 在设备长连接上执行一段操作；连接不存在或已断会自动重连登录 */
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
        log.info("OLT 长连接管理已启动：每设备 1 条常驻连接，保活开关默认{}（{}分钟探活一次）",
                keepaliveEnabled ? "开" : "关", keepaliveIntervalMs / 60000);
    }

    @PreDestroy
    public void stop() {
        if (keeper != null) {
            keeper.shutdownNow();
        }
        for (DeviceChannel channel : devices.values()) {
            channel.executor.shutdownNow();
            OltCliSession session = channel.session;
            if (session != null) {
                closeQuietly(session);
            }
        }
        devices.clear();
    }

    /** 在设备的常驻连接上执行任务：第一次会建连登录，之后一直复用同一条连接（断了才重连） */
    public <T> T execute(OltDevice device, Charset charset, boolean charsetStrict,
                         SessionTask<T> task) throws IOException {
        DeviceChannel channel = devices.computeIfAbsent(poolKey(device), DeviceChannel::new);
        AtomicBoolean started = new AtomicBoolean(false);
        Future<T> future;
        try {
            future = channel.executor.submit(() -> {
                started.set(true);
                return runOn(channel, device, charset, charsetStrict, task);
            });
        } catch (RejectedExecutionException e) {
            throw new OltTelnetException("OLT " + device.getHost() + " 的会话通道已关闭");
        }
        try {
            // 前面有任务在跑时限时排队；一旦轮到自己开始执行，任务本身耗时再长也要等完
            //（与旧会话池一致：自己的任务必然等完，只有排队超过上限才报设备忙）
            return future.get(SESSION_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (started.get()) {
                return awaitOwn(future, device);
            }
            future.cancel(false);
            OltCliSession session = channel.session;
            if (session != null && session.isSaving()) {
                // 卡在保存上的任务多半是 save：写 flash 期间设备不接任何命令，
                // 排队等它没有意义，直接给前端一个能看懂的提示
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

    /** 自己的任务已经开跑：一直等到做完（任务内部的命令超时自会兜底） */
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

    /** 把执行线程里的异常还原成调用方的 IOException / RuntimeException */
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

    /** 在通道线程上跑：保证同一条连接同一时刻只有一段「发命令 + 收回显」，天然串行、无需会话锁 */
    private <T> T runOn(DeviceChannel channel, OltDevice device, Charset charset, boolean charsetStrict,
                        SessionTask<T> task) throws IOException {
        OltCliSession session = channel.session;
        if (session != null) {
            // 只核对身份与通道是否还活着：活着的连接原样沿用，不重登录、不发探活命令
            if (!matches(channel, device, charset, charsetStrict) || !session.isAlive()) {
                closeQuietly(session);
                if (channel.session == session) {
                    channel.session = null;
                }
                session = null;
            }
        }
        if (session == null) {
            session = OltCliSession.open(device, charset, connectTimeoutMs, loginTimeoutMs, commandTimeoutMs);
            channel.session = session;
            channel.charset = charset;
            channel.username = OltCliSession.usernameOf(device);
            channel.password = OltCliSession.passwordOf(device);
            channel.vendor = OltVendor.of(device.getVendor());
        }
        // save 的缓冲期内不接新任务：提示符刚回来时个别固件还在收尾，马上发命令会被拒
        if (session.inSaveGrace()) {
            throw new OltTelnetException("OLT " + device.getHost()
                    + " 刚保存完配置，请等几秒再操作");
        }
        // 新任务只清上一轮记录：登录态、当前视图（config/接口视图）原样沿用
        session.beginTask();
        try {
            return task.run(session);
        } finally {
            long now = System.currentTimeMillis();
            channel.lastUsedAt = now;
            // 刚跑完任务连接必然是活的，探活计时从这里重新起算
            channel.lastKeepaliveAt = now;
            OltCliSession current = session;
            if (!current.isAlive()) {
                // 任务途中设备把连接断了：这条连接作废，下次任务再建（只建这一次）
                closeQuietly(current);
                if (channel.session == current) {
                    channel.session = null;
                }
            }
        }
    }

    /** 复用前核对身份：账号、密码、厂商、字符集都对得上才复用，对不上就重连 */
    private static boolean matches(DeviceChannel channel, OltDevice device, Charset charset, boolean charsetStrict) {
        if (charsetStrict && !channel.charset.equals(charset)) {
            return false;
        }
        return channel.username.equals(OltCliSession.usernameOf(device))
                && channel.password.equals(OltCliSession.passwordOf(device))
                && channel.vendor == OltVendor.of(device.getVendor());
    }

    /** 后台巡检（15 秒一拍）：只投递维护任务到通道线程，和业务任务天然串行，不碰正在用的连接 */
    private void sweep() {
        boolean keepalive = keepaliveEnabled;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, DeviceChannel> entry : devices.entrySet()) {
            DeviceChannel channel = entry.getValue();
            boolean due = keepalive
                    ? now - channel.lastKeepaliveAt >= keepaliveIntervalMs
                    : now - channel.lastUsedAt >= IDLE_QUIT_MS;
            if (!due) {
                continue;
            }
            try {
                channel.executor.execute(() -> maintain(channel, keepalive));
            } catch (RejectedExecutionException ignored) {
                // 通道已随停机流程关闭
            }
        }
    }

    /** 通道线程上的维护动作：探活保活，或未开保活时把空闲连接 quit 掉 */
    private void maintain(DeviceChannel channel, boolean keepalive) {
        OltCliSession session = channel.session;
        if (session == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (keepalive) {
            // 排队期间业务任务刚跑完的话就不再白发探活
            if (now - channel.lastKeepaliveAt < keepaliveIntervalMs) {
                return;
            }
            // 探活只敲一个回车：不改视图、不发业务命令，更不会多占一个 OLT 登录名额
            if (session.isAlive() && session.ping(commandTimeoutMs)) {
                channel.lastKeepaliveAt = System.currentTimeMillis();
            } else {
                log.debug("OLT 长连接：{} 探活失败，关闭待下次重建", channel.key);
                closeQuietly(session);
                if (channel.session == session) {
                    channel.session = null;
                }
            }
        } else {
            if (now - channel.lastUsedAt < IDLE_QUIT_MS) {
                return;
            }
            log.debug("OLT 长连接（未开保活）：{} 空闲超过 1 分钟，quit 退出登录", channel.key);
            session.logoutAndClose();
            if (channel.session == session) {
                channel.session = null;
            }
        }
    }

    /** 连接管理状态：保活开关、探活周期、每台设备的连接在位情况（管理页展示用） */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("keepaliveEnabled", keepaliveEnabled);
        status.put("keepaliveIntervalMs", keepaliveIntervalMs);
        status.put("longConnection", true);
        status.put("idleQuitMs", IDLE_QUIT_MS);
        // 管理页下拉只认固定几档：周期若不是整档（比如后端配了 3 分钟），就近落一档显示，
        // 免得下拉框空着；换算与落档都在这里做，页面不再自己算分钟
        status.put("keepaliveMinutes", keepaliveMinutes());
        status.put("keepaliveMinuteOptions", KEEPALIVE_MINUTE_OPTIONS);
        // 「没开保活时连接闲多久退出」的文案：秒数换成分钟，页面提示语直接用
        status.put("idleQuitText", (IDLE_QUIT_MS / 60000) + " 分钟");
        Map<String, Boolean> connections = new LinkedHashMap<>();
        Map<String, Boolean> saving = new LinkedHashMap<>();
        devices.forEach((key, channel) -> {
            OltCliSession session = channel.session;
            connections.put(key, session != null && session.isAlive());
            saving.put(key, session != null && session.isSaving());
        });
        status.put("deviceConnections", connections);
        // 页面据此显示「正在保存配置」之类的提示
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

    /** 一台设备的常驻通道：单线程排队执行器（串行化收发，天然互斥）+ 唯一的登录态连接 */
    private static final class DeviceChannel {
        final String key;
        /** 单线程执行器：同一台设备的所有命令都在这条线程上排队，无需再上锁 */
        final ThreadPoolExecutor executor;
        /** 常驻连接（登录态），断了/身份变了才重建；只有通道线程与停机流程会读写 */
        volatile OltCliSession session;
        volatile long lastUsedAt;
        volatile long lastKeepaliveAt;
        // 下面三项是当前连接的身份快照，只在通道线程上读写
        Charset charset;
        String username;
        String password;
        OltVendor vendor;

        DeviceChannel(String key) {
            this.key = key;
            ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1,
                    60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                    runnable -> {
                        Thread thread = new Thread(runnable, "olt-cli-" + key);
                        thread.setDaemon(true);
                        return thread;
                    });
            // 空闲设备不占线程：线程闲 60 秒就退出，连接还在，下次任务再起线程接着用
            pool.allowCoreThreadTimeOut(true);
            this.executor = pool;
        }
    }
}
