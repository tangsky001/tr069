/**
 * @author: tangsky001
 * @date:   2026年11月15日
 *
 */

package com.PilotACS.sip.rtp;

import java.util.Map;
import java.util.TreeMap;

/** 简单抖动缓冲：按序号重排、延迟深度个包输出、丢包补空（返回 null 由上层填静音） */
public class JitterBuffer {

    private final TreeMap<Integer, byte[]> slots = new TreeMap<>();
    private final int capacity;
    private final long timeoutMs;
    private int expectedSeq = -1;
    private long lastOutputAt;

    public JitterBuffer(int capacityPackets, long timeoutMs) {
        this.capacity = Math.max(2, capacityPackets);
        this.timeoutMs = timeoutMs;
    }

    public synchronized void push(int seq, byte[] payload) {
        if (expectedSeq < 0) {
            expectedSeq = seq;
        }
        // 严重乱序（迟到过多）直接跳到新序号
        int diff = seq - expectedSeq;
        if (diff < -capacity * 4) {
            slots.clear();
            expectedSeq = seq;
        }
        slots.put(seq, payload);
        if (slots.size() > capacity * 3) {
            int first = slots.firstKey();
            slots.remove(first);
            expectedSeq = Math.max(expectedSeq, first + 1);
        }
    }

    /** 到输出时间则按序弹出，不够深度或超时也放行（保流畅） */
    public synchronized byte[] poll() {
        if (expectedSeq < 0 || slots.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (lastOutputAt == 0) {
            lastOutputAt = now;
        }
        if (slots.size() < capacity && now - lastOutputAt < timeoutMs) {
            return null;
        }
        lastOutputAt = now;
        Map.Entry<Integer, byte[]> entry = slots.pollFirstEntry();
        int seq = entry.getKey();
        byte[] out = seq >= expectedSeq ? entry.getValue() : null;
        expectedSeq = Math.max(expectedSeq, seq + 1);
        return out;
    }

    public synchronized void clear() {
        slots.clear();
        expectedSeq = -1;
        lastOutputAt = 0;
    }
}
