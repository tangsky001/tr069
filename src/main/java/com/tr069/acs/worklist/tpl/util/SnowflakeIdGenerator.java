package com.tr069.acs.worklist.tpl.util;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 雪花 ID 生成器。结构：
 * <pre>
 *   0 | 0000000 00000000 00000000 00000000 00000000 0 | 00000 | 00000 | 000000000000
 *   符号 |                时间戳(ms)                     | 数据中心 | 节点  |    序列
 * </pre>
 * 总长 64 位，从 1970 起可用约 69 年。
 *
 * <p>节点号走 {@code tr069tpl.snowflake-node-id} 配置（多节点部署时手动分配避免主键撞车）。
 * 单进程内序列号自增保证唯一，跨毫秒/同毫秒都自增；时钟回拨容忍：检测到回拨后等待追上，
 * 极端情况下抛错避免主键撞车。</p>
 */
@Component
public class SnowflakeIdGenerator {

    /** 起始时间戳：2026-01-01，简化运算 */
    private static final long EPOCH = 1767225600000L;

    private static final long NODE_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_NODE_ID = (1L << NODE_BITS) - 1L;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1L;

    private static final long NODE_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + NODE_BITS;

    private final long nodeId;
    private long lastTimestamp = -1L;
    private final AtomicLong sequence = new AtomicLong(0);

    public SnowflakeIdGenerator(@org.springframework.beans.factory.annotation.Value("${tr069tpl.snowflake-node-id:1}") long nodeId) {
        if (nodeId < 0 || nodeId > MAX_NODE_ID) {
            throw new IllegalArgumentException("snowflake nodeId out of range [0, " + MAX_NODE_ID + "]: " + nodeId);
        }
        this.nodeId = nodeId;
    }

    /**
     * 生成下一个 ID，线程安全。
     */
    public synchronized long nextId() {
        long ts = System.currentTimeMillis();
        if (ts < lastTimestamp) {
            // 时钟回拨：等待追上
            long offset = lastTimestamp - ts;
            if (offset > 5000) {
                throw new IllegalStateException("时钟回拨超过 5 秒，拒绝发号: offset=" + offset);
            }
            try {
                Thread.sleep(offset);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待时钟追上时被中断", e);
            }
            ts = System.currentTimeMillis();
        }
        long seq;
        if (ts == lastTimestamp) {
            seq = sequence.incrementAndGet() & SEQUENCE_MASK;
            if (seq == 0) {
                // 同毫秒内序列号用尽，阻塞到下一毫秒
                ts = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence.set(0);
            seq = 0;
        }
        lastTimestamp = ts;
        return ((ts - EPOCH) << TIMESTAMP_SHIFT)
                | (nodeId << NODE_SHIFT)
                | seq;
    }

    private long waitNextMillis(long last) {
        long ts = System.currentTimeMillis();
        while (ts <= last) {
            ts = System.currentTimeMillis();
        }
        return ts;
    }
}
