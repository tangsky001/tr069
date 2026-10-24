package com.tr069.acs.queue.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis 订阅所需的监听容器。
 *
 * <p>Spring Boot 自动配置提供 {@code StringRedisTemplate}，但不提供监听容器——
 * 它是订阅才要的东西，框架不知道有没有人订阅，不做多余装配。
 * 下发唤醒要用发布订阅跨实例通知（见 {@code RpcDeliveryWaiter}），这里补上。</p>
 *
 * <p>{@code destroyMethod} 交给容器回调：监听容器内部有订阅线程和连接池引用，
 * 不关会在停机时拖住线程。</p>
 */
@Configuration
public class RedisListenerConfig {

    @Bean(destroyMethod = "destroy")
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
}
