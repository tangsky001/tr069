package com.tr069.acs.queue;

import com.tr069.acs.queue.config.QueueProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 挂起等待下发的那一跳请求：唤醒、超时、二次注册这三件事的语义。
 *
 * <p>这里最怕的是重复结算——同一个响应既写指令又写 204，
 * 或者同一设备挂了两跳互相抢指令。</p>
 */
class RpcDeliveryWaiterTest {

    private final QueueProperties properties = new QueueProperties();

    private RpcDeliveryWaiter waiter() {
        return new RpcDeliveryWaiter(properties, null);
    }

    @Test
    void 入队信号唤醒挂着的那一跳() {
        RpcDeliveryWaiter registry = waiter();
        Counter counter = new Counter();
        registry.register("SN1", counter);

        assertThat(registry.wakeup("SN1")).isTrue();

        assertThat(counter.signals.get()).as("被唤醒一次").isEqualTo(1);
        assertThat(counter.timeouts.get()).isZero();
    }

    @Test
    void 多次入队只结算一次() {
        RpcDeliveryWaiter registry = waiter();
        Counter counter = new Counter();
        registry.register("SN1", counter);

        assertThat(registry.wakeup("SN1")).isTrue();
        // 唤醒过之后槽位就空了：后续信号没本节点的请求要叫，
        // 指令会在设备下一次上报时被取走，不会丢
        assertThat(registry.wakeup("SN1")).isFalse();

        assertThat(counter.signals.get()).isEqualTo(1);
    }

    @Test
    void 超时与唤醒竞争时只结算一次() {
        RpcDeliveryWaiter registry = waiter();
        Counter counter = new Counter();
        registry.register("SN1", counter);

        // 设备正好在超时那一刻把上一问的响应发了回来、编排出的下一问随即入队，
        // 两条线会几乎同时结算这一跳
        assertThat(registry.wakeup("SN1")).isTrue();
        assertThat(registry.expire("SN1")).isFalse();

        assertThat(counter.signals.get() + counter.timeouts.get())
                .as("同一个响应不能既写指令又写 204").isEqualTo(1);
    }

    @Test
    void 同一设备二次注册时旧的那跳先收尾() {
        RpcDeliveryWaiter registry = waiter();
        Counter first = new Counter();
        Counter second = new Counter();

        registry.register("SN1", first);
        registry.register("SN1", second);

        assertThat(first.timeouts.get()).as("旧的一跳按超时收尾，不去抢新一跳的指令").isEqualTo(1);

        registry.wakeup("SN1");
        assertThat(second.signals.get()).isEqualTo(1);
        assertThat(first.signals.get()).isZero();
    }

    @Test
    void 没有挂起的请求时唤醒返回false() {
        RpcDeliveryWaiter registry = waiter();
        assertThat(registry.wakeup("未挂起的SN")).isFalse();
    }

    /** 记录这一跳被结算了几次。 */
    private static final class Counter implements RpcDeliveryWaiter.Waiter {

        private final AtomicInteger signals = new AtomicInteger();

        private final AtomicInteger timeouts = new AtomicInteger();

        @Override
        public void onSignal() {
            signals.incrementAndGet();
        }

        @Override
        public void onTimeout() {
            timeouts.incrementAndGet();
        }
    }
}
