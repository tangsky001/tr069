package com.tr069.acs.olt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tr069.acs.olt.entity.OltDevice;
import com.tr069.acs.olt.mapper.OltDeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * OLT 定时自动采集：启动后先采一轮，之后按固定间隔重新登录每台 OLT，刷板卡、端口 ONT 数和版本信息。
 *
 * <p>板卡插拔、端口上的 ONT 数量、设备重启后的运行时长，只有去设备上看才知道，
 * 光靠页面是不可能变的——之前只能人工点「同步」，忘了点页面就一直是旧数据。
 * 这里后台按间隔采集，页面照样读 olt_board，看到的自然就是最新的。</p>
 *
 * <p>几个刻意的取舍：</p>
 * <ul>
 *   <li><strong>串行采集</strong>：OLT 的 Telnet 并发会话数很小（华为大多只允许几个），
 *       一台一台来比并发安全；{@code running} 还保证上一轮没跑完时本轮跳过。</li>
 *   <li><strong>失败退避</strong>：某台连不上时按连续失败次数把它的采集间隔逐步拉长
 *       （最多 6 倍），连好后恢复。否则一台掉线的设备会让每轮都多等一个登录超时，
 *       设备越多整轮越慢。</li>
 *   <li><strong>单轮有上限</strong>：超过 {@code maxRoundMillis} 剩下的设备留到下一轮，
 *       不让一轮无限拖下去。</li>
 *   <li><strong>失败不抛</strong>：单台失败记日志写采集记录就行，不能影响其它 OLT，
 *       更不能影响 ACS 本身。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OltAutoRefreshService {

    /** 采集记录里的操作人，页面上一眼能看出是后台自动跑的还是谁手点的 */
    private static final String OPERATOR = "自动刷新";

    /** 连续失败后最多把间隔拉到几倍 */
    private static final int MAX_BACKOFF = 6;

    /** 自动采集间隔(毫秒)：写死 5 分钟。板卡插拔、端口 ONT 数这些只有设备自己知道，
     *  靠页面上人工点「同步」迟早会忘，这里固定周期刷新，不提供关闭开关 */
    private static final long INTERVAL_MILLIS = 5 * 60 * 1000L;

    /** 启动后多久跑第一轮(毫秒)：连上就采一次，只留出建表和连接池初始化的时间 */
    private static final long INITIAL_DELAY_MILLIS = 5 * 1000L;

    private final OltDeviceMapper deviceMapper;

    private final OltService oltService;

    /** 单轮总耗时上限(毫秒)，超了剩下的设备留到下一轮 */
    private long maxRoundMillis = 5 * 60 * 1000L;

    /** 每台设备的连续失败次数 */
    private final Map<Long, Integer> failures = new ConcurrentHashMap<>();

    /** 每台设备最早可以再采一次的时间戳，失败退避用 */
    private final Map<Long, Long> retryAt = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 一轮自动采集。
     */
    @Scheduled(fixedDelay = INTERVAL_MILLIS, initialDelay = INITIAL_DELAY_MILLIS)
    public void refreshAll() {
        if (!running.compareAndSet(false, true)) {
            log.info("OLT 自动刷新：上一轮还没跑完，本轮跳过");
            return;
        }
        try {
            List<OltDevice> devices = deviceMapper.selectList(
                    new LambdaQueryWrapper<OltDevice>().orderByAsc(OltDevice::getId));
            cleanupRemoved(devices);
            long deadline = System.currentTimeMillis() + maxRoundMillis;
            int ok = 0;
            int failed = 0;
            int skipped = 0;
            for (OltDevice device : devices) {
                if (System.currentTimeMillis() > deadline) {
                    log.warn("OLT 自动刷新超出单轮上限，剩余设备下一轮处理: 剩余={}", devices.size() - ok - failed - skipped);
                    break;
                }
                Long waitUntil = retryAt.get(device.getId());
                if (waitUntil != null && waitUntil > System.currentTimeMillis()) {
                    skipped++;
                    continue;
                }
                try {
                    oltService.sync(device.getId(), OPERATOR);
                    failures.remove(device.getId());
                    retryAt.remove(device.getId());
                    ok++;
                } catch (Exception e) {
                    int streak = Math.min(MAX_BACKOFF, failures.merge(device.getId(), 1, Integer::sum));
                    retryAt.put(device.getId(), System.currentTimeMillis() + INTERVAL_MILLIS * streak);
                    failed++;
                    log.info("OLT 自动刷新失败 id={}, 下次退避 {} 分钟: {}", device.getId(),
                            INTERVAL_MILLIS * streak / 60000, e.getMessage());
                }
            }
            log.info("OLT 自动刷新完成: 成功={}, 失败={}, 退避跳过={}", ok, failed, skipped);
        } finally {
            running.set(false);
        }
    }

    /**
     * 清掉已删除设备的退避记录：台账删了之后内存里的记录不该留着（顺带防内存泄漏）。
     */
    private void cleanupRemoved(List<OltDevice> devices) {
        Set<Long> alive = devices.stream().map(OltDevice::getId).collect(Collectors.toSet());
        failures.keySet().removeIf(id -> id == null || !alive.contains(id));
        retryAt.keySet().removeIf(id -> id == null || !alive.contains(id));
    }
}
