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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * OLT Telnet 会话池：每台设备固定 2 个会话轮换，行为由管理页的「保活开关」决定，
 * 开关是运行时状态（{@link #setKeepalive}），不落在配置文件里。
 *
 * <p>保活开：第一次用到某台 OLT 时登录建立会话，之后所有命令沿用池中的会话；
 * 两个会话轮换借出（先归还的先借出），都在忙时后来的请求排队等归还（不报「会话忙」）。
 * 后台线程每隔保活周期（默认 2 分钟，管理页可调）对空闲会话发 {@code display client}
 * 探活：活着继续用，死了下次借出时重新登录建立。</p>
 *
 * <p>保活关（默认）：会话 1 分钟没人用就 {@code quit} 优雅退出登录，把设备的会话
 * 名额当场还回去；之后的全局轮询采集要用时自己再登录。1 分钟内又来了请求，
 * 池里的会话还是热的，照样复用，不白付登录。</p>
 *
 * <p>复用前 {@link OltCliSession#reviveForPool} 体检：发 {@code display client}
 * 探活、停在子视图里退回基础视图、清上一轮回显——即使保活线程没跑到，借到死会话
 * 也会当场重开，不会把死会话交给业务命令。</p>
 *
 * <p>注意：池化会话是用「借出那一刻」的账号/字符集登录的，台账里改了密码后旧会话
 * 仍按旧密码存活到自然失效；改字符集/厂商的会话会在下次借出时因不匹配被丢弃重开。</p>
 */
@Slf4j
@Component
public class OltSessionPool {

    /** 每台 OLT 的会话数（轮换使用），也就是 OLT 侧最多占用的登录名额数 */
    private static final int SESSIONS_PER_DEVICE = 2;

    /** 不保活时：会话空闲超过 1 分钟就 quit 退出登录 */
    private static final long IDLE_QUIT_MS = 60_000L;

    /** 保活探活的默认周期：2 分钟 */
    private static final long DEFAULT_KEEPALIVE_INTERVAL_MS = 120_000L;

    /** 后台巡检节拍：探活到期与否、不保活模式的 1 分钟退出都按这个粒度检查 */
    private static final long SWEEP_TICK_MS = 15_000L;

    @Value("${acs.olt.connect-timeout-ms:8000}")
    private int connectTimeoutMs;

    @Value("${acs.olt.login-timeout-ms:15000}")
    private int loginTimeoutMs;

    @Value("${acs.olt.command-timeout-ms:20000}")
    private int commandTimeoutMs;

    /** 管理页的保活开关（运行时，不进配置文件），默认关：不保活 */
    private volatile boolean keepaliveEnabled = false;

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

    /**
     * 借一个会话执行任务：优先复用空闲会话（省掉登录 + 厂商识别 + 字符集探测），
     * 没有可复用的就新开一条；用完退回基础视图后归还池中。
     *
     * @param device        OLT 连接参数（密码已还原为明文）
     * @param charset       本次操作要求的回显字符集
     * @param charsetStrict true：池中会话字符集必须一致才复用（指定了编码、GBK 重试）；
     *                      false：台账是「自动」编码，池中会话用哪种都行，解错了上层会重试
     */
    public <T> T execute(OltDevice device, Charset charset, boolean charsetStrict,
                         SessionTask<T> task) throws IOException {
        DevicePool pool = pools.computeIfAbsent(poolKey(device), key -> new DevicePool(SESSIONS_PER_DEVICE));
        try {
            // 两个会话都在忙时排队等，不报「会话忙」：单条命令有超时兜底，
            // 持有会话的任务总会结束，等到归还为止
            pool.permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OltTelnetException("等待 OLT " + device.getHost() + " 会话被中断");
        }
        try {
            Pooled pooled = takeIdle(pool, device, charset, charsetStrict);
            OltCliSession session = pooled == null
                    ? OltCliSession.open(device, charset, connectTimeoutMs, loginTimeoutMs, commandTimeoutMs)
                    : pooled.session;
            try {
                return task.run(session);
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

    /**
     * 取一个可复用的空闲会话：账号/字符集/厂商对得上、体检通过；取不到返回 null。
     * 从队头取（先归还的先借出），两个会话轮换使用，不会出现一个一直热着、
     * 另一个一直闲到被设备踢掉。
     */
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
                    OltCliSession.usernameOf(device), OltVendor.of(device.getVendor())));
        }
    }

    private static boolean matches(Pooled pooled, OltDevice device, Charset charset, boolean charsetStrict) {
        if (charsetStrict && !pooled.charset.equals(charset)) {
            return false;
        }
        return pooled.username.equals(OltCliSession.usernameOf(device))
                && pooled.vendor == OltVendor.of(device.getVendor());
    }

    /**
     * 后台巡检（15 秒一拍，按开关分两种行为）：
     * 保活开 —— 到了探活周期的空闲会话发 {@code display client} 探活，
     *           活着放回去继续用，死了关掉（下次借出时重新登录建立）；
     * 保活关 —— 空闲超过 1 分钟没人用的会话 {@code quit} 优雅退出登录，
     *           把设备的会话名额当场还回去，之后轮询采集要用时自己再登录。
     * 在设备池的锁内做：借出/归还/巡检不会同时对同一条会话动手。
     */
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

    /**
     * 管理页的保活开关。
     *
     * @param enabled    开：2 会话轮换 + 定期 display client 探活；
     *                   关：会话 1 分钟没人用就 quit 退出登录
     * @param intervalMs 探活周期(毫秒)，null 保持不变；范围 10 秒 ~ 1 小时
     * @return 最新状态
     */
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

    private static int idleCount(DevicePool pool) {
        synchronized (pool) {
            return pool.idle.size();
        }
    }

    private static String poolKey(OltDevice device) {
        int port = device.getPort() == null || device.getPort() <= 0 ? 23 : device.getPort();
        return device.getHost() + ":" + port;
    }

    private static void closeQuietly(OltCliSession session) {
        try {
            session.close();
        } catch (RuntimeException e) {
            log.debug("关闭 OLT 会话失败(忽略): {}", e.getMessage());
        }
    }

    /** 一台设备的池：并发名额 + 空闲会话队列 */
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
        final OltVendor vendor;
        volatile long lastUsedAt;
        volatile long lastKeepaliveAt;

        Pooled(OltCliSession session, Charset charset, String username, OltVendor vendor) {
            this.session = session;
            this.charset = charset;
            this.username = username;
            this.vendor = vendor;
            long now = System.currentTimeMillis();
            this.lastUsedAt = now;
            this.lastKeepaliveAt = now;
        }
    }
}
