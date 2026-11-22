/**
 * @author: tangsky001
 * @date:   2026年11月19日
 *
 */

package com.PilotACS.cpe.redis;

import com.PilotACS.cpe.config.CpeProperties;
import com.PilotACS.common.log.Logs;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/** SN/IP 粒度令牌桶限流（cpe:limit:token:{id}）。 */
@Component
public class SnRateLimiter {

    private static final RedisScript<Long> TOKEN_BUCKET = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refillPerSec = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local ttl = tonumber(ARGV[4])
            local data = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil or ts == nil then
              redis.call('HSET', key, 'tokens', capacity - 1, 'ts', now)
              redis.call('EXPIRE', key, ttl)
              return 1
            end
            tokens = math.min(capacity, tokens + (now - ts) * refillPerSec)
            if tokens < 1 then
              redis.call('HSET', key, 'ts', now)
              return 0
            end
            redis.call('HSET', key, 'tokens', tokens - 1, 'ts', now)
            redis.call('EXPIRE', key, ttl)
            return 1
            """, Long.class);

    private final StringRedisTemplate template;
    private final CpeProperties props;

    public SnRateLimiter(@Qualifier("sessionStringRedisTemplate") StringRedisTemplate template,
                         CpeProperties props) {
        this.template = template;
        this.props = props;
    }

    /** 尝试取一个令牌。 */
    public boolean tryAcquire(String id) {
        if (!props.isRateLimitEnabled()) {
            return true;
        }
        long now = System.currentTimeMillis() / 1000L;
        int ttlSec = Math.max(props.getSessionTtlSeconds() * 2, 600);
        try {
            Long allow = template.execute(TOKEN_BUCKET,
                    List.of(CpeRedisKeys.token(id)),
                    String.valueOf(props.getRateLimitCapacity()),
                    String.valueOf(props.getRateLimitRefillPerMinute() / 60.0),
                    String.valueOf(now),
                    String.valueOf(ttlSec));
            return allow != null && allow == 1L;
        } catch (Exception e) {
            Logs.warn(Logs.Module.CPE, "限流检查异常，放行", "主体", id, "原因", e.getMessage());
            return true;
        }
    }
}
