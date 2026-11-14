/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.802
 *
 */

package com.PilotACS.conf;

import com.PilotACS.common.log.Logs;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

// 启动时探测 Redis，未运行则禁止启动
@Component
@RequiredArgsConstructor
public class RedisStartupChecker {

    private final StringRedisTemplate redisTemplate;
    private final RedisProperties redisProperties;

    @PostConstruct
    public void check() {
        String addr = redisProperties.getHost() + ":" + redisProperties.getPort();
        try {
            String pong = redisTemplate.execute((RedisCallback<String>) conn -> conn.ping());
            Logs.info(Logs.Module.CHECK, "Redis 连接正常", "地址", addr, "响应", pong);
        } catch (Exception e) {
            throw new IllegalStateException("Redis 未运行或无法连接 " + addr + "，请先启动 Redis 再启动本服务");
        }
    }
}
