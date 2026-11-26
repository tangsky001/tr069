package com.PilotACS.cpe.server;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 设备"空 POST 挂起跳"注册表（对齐 master 的 RpcDeliveryWaiter）。
 *
 * CWMP 只能把指令捎带在设备自己发起的那一轮 HTTP 会话里。master 用 Servlet
 * AsyncContext 把设备的空 POST 挂起，指令入队时 signal(sn) 立刻唤醒同一跳下发；
 * 我们这边由虚拟线程承载挂起跳，效果等价：指令到达瞬间唤醒，不需要 CR 反踢，
 * 也不需要等设备 5 分钟周期上报。
 */
@Component
public class DeliveryWaiter {

    /** sn → 挂起跳的唤醒信号（同一 SN 同一时刻最多一个挂起跳） */
    private final Map<String, Semaphore> signals = new ConcurrentHashMap<>();

    /** 设备空 POST 进入等待时登记，返回本次等待的信号票 */
    public Semaphore register(String sn) {
        Semaphore sem = new Semaphore(0);
        signals.put(sn, sem);
        return sem;
    }

    /** 指令入队/会话就绪时唤醒该设备挂起的那一跳 */
    public void signal(String sn) {
        Semaphore sem = signals.get(sn);
        if (sem != null) {
            sem.release();
        }
    }

    /** 设备当前是否有挂起的空 POST（可直接捎带下发） */
    public boolean hasPendingHop(String sn) {
        return signals.containsKey(sn);
    }

    /** 等信号或超时；true 表示被指令唤醒（应立刻重查队列） */
    public boolean await(Semaphore ticket, long timeoutMs) {
        if (timeoutMs <= 0) {
            return false;
        }
        try {
            boolean got = ticket.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
            // 清掉等待期间堆积的多余信号，避免下一轮空转
            ticket.drainPermits();
            return got;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 挂起跳结束时注销 */
    public void unregister(String sn, Semaphore ticket) {
        signals.remove(sn, ticket);
    }
}
