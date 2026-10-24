package com.tr069.acs.acs.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 同一设备的会话互斥锁，防止两个实例同时对一台设备编排下发导致互相插队、
 * 超时判定基准混乱。
 *
 * <p>抢占 + TTL 兜底：进程崩溃没来得及释放时，锁到期自动失效。</p>
 */
@Slf4j
@Component
public class CwmpSessionMutex {

    private static final String KEY_PREFIX = "acs:cwmp_session:";

    /** 只在值匹配时才许删除，免得把别的实例刚抢到的锁给摘了。 */
    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then "
                            + "return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);

    /**
     * Redis 缺席时不拦。互斥是为了下发有序，为了它把设备整个挡在外面是本末倒置——
     * 退化成并发下发，代价远小于设备连不上来。
     */
    @Autowired(required = false)
    private StringRedisTemplate redis;

    @Value("${acs.session.session-lock-enabled:true}")
    private boolean enabled;

    /**
     * 持锁上限（秒）。要盖住一轮会话的最长静默时长（等空 POST 的超时 + 余量），
     * 短了会在会话还活着的时候被别人抢走。
     */
    @Value("${acs.session.session-lock-ttl-seconds:90}")
    private long ttlSeconds;

    /**
     * 抢占设备的会话锁。
     *
     * @return {@code true} 抢到；{@code false} 别的实例正握着这一台的会话
     */
    public boolean acquire(String sn, String sessionId) {
        if (!usable(sn, sessionId)) {
            return true;
        }
        try {
            String key = KEY_PREFIX + sn;
            Boolean acquired = redis.opsForValue()
                    .setIfAbsent(key, sessionId, Duration.ofSeconds(ttlSeconds));
            if (Boolean.TRUE.equals(acquired)) {
                return true;
            }
            // 已经是自己握着（同一设备重连、Container把它们分到本实例）：放行并顺手续期
            String holder = redis.opsForValue().get(key);
            if (sessionId.equals(holder)) {
                redis.expire(key, Duration.ofSeconds(ttlSeconds));
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("会话锁抢占失败，放行但不保证串行: sn={}, err={}", sn, e.getMessage());
            return true;
        }
    }

    /**
     * 放开本轮会话锁，让设备的下一次请求（可能落到别的实例）能进来。
     */
    public void release(String sn, String sessionId) {
        if (!usable(sn, sessionId)) {
            return;
        }
        try {
            redis.execute(COMPARE_AND_DELETE, List.of(KEY_PREFIX + sn), sessionId);
        } catch (Exception e) {
            log.warn("会话锁释放失败，等待过期: sn={}, err={}", sn, e.getMessage());
        }
    }

    private boolean usable(String sn, String sessionId) {
        return enabled && redis != null && sn != null && sessionId != null && !sessionId.isBlank();
    }
}
