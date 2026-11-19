/**
 * @author: tangsky001
 * @date:   2026年11月19日
 *
 */

package com.PilotACS.cpe.redis;

/** CPE 模块 Redis key 规划（故障域隔离）： */
public final class CpeRedisKeys {

    private CpeRedisKeys() {
    }

    public static final String SESSION_PREFIX = "cpe:session:";
    public static final String SID_PREFIX = "cpe:sid:";
    public static final String INFO_PREFIX = "cpe:info:";
    public static final String PARAM_TREE_PREFIX = "cpe:params:tree:";
    public static final String TOKEN_PREFIX = "cpe:limit:token:";
    public static final String TASK_QUEUE = "tr069:queue:rpc";

    /** 会话生命周期 key（DB1） */
    public static String session(String sn) {
        return SESSION_PREFIX + sn;
    }

    /** sessionId 反查 key（DB1） */
    public static String sid(String sessionId) {
        return SID_PREFIX + sessionId;
    }

    /** 设备档案缓存 key（DB1） */
    public static String info(String sn) {
        return INFO_PREFIX + sn;
    }

    /** 设备存量参数树缓存 key（DB1），落库时主动失效 */
    public static String paramTree(String sn) {
        return PARAM_TREE_PREFIX + sn;
    }

    /** 令牌桶限流 key（DB1；限流在会话之前，不与会话争 OPS） */
    public static String token(String id) {
        return TOKEN_PREFIX + id;
    }

    /** 从过期事件 key 反解 sn，非会话 key 返回 null */
    public static String snOfSessionKey(String key) {
        if (key != null && key.startsWith(SESSION_PREFIX)) {
            return key.substring(SESSION_PREFIX.length());
        }
        return null;
    }
}
