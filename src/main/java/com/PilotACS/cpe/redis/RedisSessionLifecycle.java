/**
 * @author: tangsky001
 * @date:   2026年11月19日
 *
 */

package com.PilotACS.cpe.redis;

import com.PilotACS.cpe.config.CpeProperties;
import com.PilotACS.cpe.session.CwmpSession;
import com.PilotACS.common.log.Logs;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** 会话生命周期 Redis 记录（DB1 Hash） */
@Component
public class RedisSessionLifecycle {

    private static final String RPC_IDLE = "IDLE";
    private static final String RPC_RUNNING = "RUNNING";

    /** CAS 下发 RUNNING */
    private static final RedisScript<Long> CAS_RUNNING = new DefaultRedisScript<>("""
            local st = redis.call('HGET', KEYS[1], 'rpcState')
            local tid = redis.call('HGET', KEYS[1], 'currentTaskId')
            if st == 'RUNNING' and tid ~= nil and tid ~= '' and tid ~= ARGV[1] then
              return 0
            end
            redis.call('HSET', KEYS[1],
              'rpcState', 'RUNNING', 'currentTaskId', ARGV[1],
              'version', redis.call('HINCRBY', KEYS[1], 'version', 1))
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
            return 1
            """, Long.class);

    /** CAS 回到 IDLE */
    private static final RedisScript<Long> CAS_IDLE = new DefaultRedisScript<>("""
            local tid = redis.call('HGET', KEYS[1], 'currentTaskId')
            if ARGV[1] ~= '' and tid ~= nil and tid ~= '' and tid ~= ARGV[1] then
              return 0
            end
            redis.call('HSET', KEYS[1],
              'rpcState', 'IDLE', 'currentTaskId', '',
              'version', redis.call('HINCRBY', KEYS[1], 'version', 1))
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
            return 1
            """, Long.class);

    private final StringRedisTemplate template;
    private final CpeProperties props;

    public RedisSessionLifecycle(@Qualifier("sessionStringRedisTemplate") StringRedisTemplate template,
                                 CpeProperties props) {
        this.template = template;
        this.props = props;
    }

    private Duration ttl() {
        return Duration.ofSeconds(Math.max(30, props.getSessionTtlSeconds()));
    }

    private String sessionKey(String sn) {
        return CpeRedisKeys.session(sn);
    }

    /** 会话登记 Lua 原子脚本 */
    private static final RedisScript<Long> REGISTER = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1])
            for i = 1, #ARGV - 1, 2 do
              redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1])
            end
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[#ARGV]))
            return 1
            """, Long.class);

    /** 新会话登记 */
    public void register(CwmpSession s) {
        try {
            String key = sessionKey(s.getSn());
            Map<String, String> fields = baseFields(s);
            java.util.List<String> argv = new java.util.ArrayList<>(fields.size() * 2 + 1);
            fields.forEach((k, v) -> {
                argv.add(k);
                argv.add(v);
            });
            argv.add(String.valueOf(ttl().toSeconds()));
            template.execute(REGISTER, java.util.List.of(key), argv.toArray());
            template.opsForValue().set(CpeRedisKeys.sid(s.getSessionId()), s.getSn(), ttl());
        } catch (Exception e) {
            Logs.warn(Logs.Module.CPE_PUSH, "会话生命周期登记失败（降级内存语义）", "sn", s.getSn(), "原因", e.getMessage());
        }
    }

    /** 会话续期 */
    public void renew(CwmpSession s) {
        try {
            Duration d = ttl();
            template.expire(sessionKey(s.getSn()), d);
            template.expire(CpeRedisKeys.sid(s.getSessionId()), d);
        } catch (Exception e) {
        }
    }

    /** 会话收尾后移除 */
    public void remove(String sn, String sessionId) {
        try {
            if (sessionId == null) {
                template.delete(sessionKey(sn));
            } else {
                Object sid = template.opsForHash().get(sessionKey(sn), "sessionId");
                if (sessionId.equals(sid)) {
                    template.delete(sessionKey(sn));
                }
            }
            if (sessionId != null) {
                template.delete(CpeRedisKeys.sid(sessionId));
            }
        } catch (Exception e) {
        }
    }

    /** RPC 下发置 RUNNING */
    public boolean rpcRunning(String sn, long taskId) {
        try {
            Long ok = template.execute(CAS_RUNNING,
                    java.util.List.of(sessionKey(sn)),
                    String.valueOf(taskId), String.valueOf(ttl().toSeconds()));
            if (ok == null || ok != 1L) {
                Logs.debug(Logs.Module.CPE_PUSH, "rpcState CAS 拒绝（已在跑其他任务）", "sn", sn, "任务", taskId);
                return false;
            }
            return true;
        } catch (Exception e) {
            Logs.warn(Logs.Module.CPE_PUSH, "rpcState 写 RUNNING 失败（降级内存语义）", "sn", sn, "原因", e.getMessage());
            return false;
        }
    }

    /** RPC 收口置 IDLE */
    public void rpcIdle(String sn, long taskId) {
        try {
            template.execute(CAS_IDLE,
                    java.util.List.of(sessionKey(sn)),
                    taskId > 0 ? String.valueOf(taskId) : "", String.valueOf(ttl().toSeconds()));
        } catch (Exception e) {
            Logs.warn(Logs.Module.CPE_PUSH, "rpcState 写 IDLE 失败（降级内存语义）", "sn", sn, "原因", e.getMessage());
        }
    }

    /** 会话 key 是否仍存活 */
    public boolean exists(String sn) {
        try {
            Boolean ok = template.hasKey(sessionKey(sn));
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            return true;
        }
    }

    /** 读取会话快照（运维/REST 排障用），不存在返回 null */
    public Map<Object, Object> sessionOf(String sn) {
        try {
            return template.opsForHash().entries(sessionKey(sn));
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> baseFields(CwmpSession s) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("sessionId", s.getSessionId());
        m.put("sn", s.getSn());
        m.put("rpcState", RPC_IDLE);
        m.put("currentTaskId", "");
        m.put("version", "1");
        m.put("lastActivity", String.valueOf(s.getLastActivity()));
        return m;
    }
}
