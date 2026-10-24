package com.tr069.acs.queue;

import com.tr069.acs.queue.config.QueueProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 挂着等待唤醒的那一跳 HTTP 请求：把请求交给容器异步挂起，指令入队时按 SN 唤醒，
 * 免得每台设备每一跳都占一个容器线程去轮询队列。
 *
 * <p>跨实例部署时入队可能发生在另一个节点，因此入队侧额外发一条 Redis 广播，
 * 各实例按 SN 唤醒自己名下挂着的那跳。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RpcDeliveryWaiter implements MessageListener {

    private final QueueProperties properties;

    /**
     * 订阅容器：拿不到就不订阅，退化为「等设备下一次上报时取走指令」，
     * 不让 Redis 缺席影响启动。
     */
    private final ObjectProvider<RedisMessageListenerContainer> listenerContainer;

    /** SN -> 当前挂着的那跳（同一设备同时只允许挂一跳，后来的先让旧的收尾）。 */
    private final Map<String, AtomicReference<Slot>> waiting = new ConcurrentHashMap<>();

    /**
     * 订阅入队广播，用于跨实例唤醒。按前缀 pattern 订阅，免得逐个 SN 维护订阅列表。
     */
    @PostConstruct
    public void subscribe() {
        RedisMessageListenerContainer container = listenerContainer.getIfAvailable();
        if (container == null) {
            log.warn("没有 Redis 监听容器，跨实例入队唤醒不生效，设备下一次上报时仍会取走指令");
            return;
        }
        try {
            container.addMessageListener(this, new PatternTopic(signalPattern()));
        } catch (Exception e) {
            log.warn("下发唤醒订阅未生效，跨实例唤醒降级为等设备下一次上报: {}", e.getMessage());
        }
    }

    /**
     * 把这一跳挂起来等指令。同一 SN 上若已挂着旧的，先让它按超时收尾
     * （回 204），免得两跳并发去抢同一条指令。
     */
    public void register(String sn, Waiter waiter) {
        AtomicReference<Slot> ref = waiting.computeIfAbsent(sn, k -> new AtomicReference<>());
        Slot previous = ref.getAndSet(new Slot(waiter));
        if (previous != null) {
            previous.expire();
        }
    }

    /**
     * 有指令入队：唤这一跳起来取指令。
     *
     * @return 是否真的唤醒了什么；返回 false 说明该设备此刻没挂着请求，
     *         指令会在它下一次上报时被取走，不会丢
     */
    public boolean wakeup(String sn) {
        AtomicReference<Slot> ref = waiting.get(sn);
        if (ref == null) {
            return false;
        }
        Slot slot = ref.getAndSet(null);
        return slot != null && slot.signal();
    }

    /**
     * 这一跳已经写回响应，把它摘掉。
     */
    public void cancel(String sn, Waiter waiter) {
        AtomicReference<Slot> ref = waiting.get(sn);
        if (ref == null) {
            return;
        }
        Slot slot = ref.get();
        if (slot != null && slot.waiter == waiter) {
            ref.compareAndSet(slot, null);
        }
    }

    /**
     * 超时到点、或容器回调了未完成事件：让这一跳收尾。
     * 已结清的槽位不会被重复触发。
     */
    public boolean expire(String sn) {
        AtomicReference<Slot> ref = waiting.get(sn);
        if (ref == null) {
            return false;
        }
        Slot slot = ref.getAndSet(null);
        return slot != null && slot.expire();
    }

    public void forget(String sn) {
        waiting.remove(sn);
    }

    /** 当前挂着等待的设备数，排障用。 */
    public int waitingCount() {
        return waiting.size();
    }

    public String signalChannel(String sn) {
        return properties.getKeyPrefix() + ":ch:rpc:" + sn;
    }

    private String signalPattern() {
        return properties.getKeyPrefix() + ":ch:rpc:*";
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), java.nio.charset.StandardCharsets.UTF_8);
        String prefix = properties.getKeyPrefix() + ":ch:rpc:";
        if (!channel.startsWith(prefix)) {
            return;
        }
        String sn = channel.substring(prefix.length());
        if (sn.isEmpty()) {
            return;
        }
        if (wakeup(sn)) {
            log.debug("收到跨实例入队广播，已唤醒挂起的请求: sn={}", sn);
        }
    }

    /**
     * 被挂起的那一跳要做的两件事。
     */
    public interface Waiter {

        /** 有指令入队了：起来试着取一条下发。 */
        void onSignal();

        /** 等到超时：回 204 结束这一轮会话。 */
        void onTimeout();
    }

    /**
     * 槽位：保证这一跳只被结算一次。唤醒与超时可能同时到达，没这道闸会既写指令又写 204。
     */
    private static final class Slot {

        private final Waiter waiter;

        private final AtomicBoolean settled = new AtomicBoolean(false);

        Slot(Waiter waiter) {
            this.waiter = waiter;
        }

        boolean signal() {
            if (!settled.compareAndSet(false, true)) {
                return false;
            }
            waiter.onSignal();
            return true;
        }

        boolean expire() {
            if (!settled.compareAndSet(false, true)) {
                return false;
            }
            waiter.onTimeout();
            return true;
        }
    }
}
