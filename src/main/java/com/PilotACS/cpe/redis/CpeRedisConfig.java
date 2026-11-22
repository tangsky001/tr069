/**
 * @author: tangsky001
 * @date:   2026年11月19日
 *
 */

package com.PilotACS.cpe.redis;

import com.PilotACS.common.log.Logs;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Redis 双库连接配置 */
@Configuration
@EnableConfigurationProperties(RedisProperties.class)
public class CpeRedisConfig {

    private final RedisProperties props;

    public CpeRedisConfig(RedisProperties props) {
        this.props = props;
    }

    private RedisStandaloneConfiguration standalone(int database) {
        RedisStandaloneConfiguration conf =
                new RedisStandaloneConfiguration(props.getHost(), props.getPort());
        conf.setDatabase(database);
        if (props.getPassword() != null && !props.getPassword().isEmpty()) {
            conf.setPassword(RedisPassword.of(props.getPassword()));
        }
        return conf;
    }

    /** DB0 主连接工厂 */
    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory(standalone(0));
    }

    /** DB1 会话域连接工厂 */
    @Bean
    public RedisConnectionFactory sessionRedisConnectionFactory() {
        return new LettuceConnectionFactory(standalone(1));
    }

    /** DB0 StringRedisTemplate */
    @Bean
    @Primary
    public StringRedisTemplate stringRedisTemplate(
            @Qualifier("redisConnectionFactory") RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /** DB1 会话域 StringRedisTemplate */
    @Bean
    public StringRedisTemplate sessionStringRedisTemplate(
            @Qualifier("sessionRedisConnectionFactory") RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /** 会话过期事件监听容器 */
    @Bean
    public RedisMessageListenerContainer sessionExpiryListenerContainer(
            @Qualifier("sessionRedisConnectionFactory") RedisConnectionFactory factory,
            SessionExpiryListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(listener, new ChannelTopic("__keyevent@1__:expired"));
        return container;
    }

    /** 启动时开启 keyspace 过期事件通知 */
    @Bean(initMethod = "ensureKeyspaceEvents")
    public KeyspaceEventsEnsurer keyspaceEventsEnsurer(
            @Qualifier("sessionStringRedisTemplate") StringRedisTemplate sessionTemplate) {
        return new KeyspaceEventsEnsurer(sessionTemplate);
    }

    public static class KeyspaceEventsEnsurer {

        private final StringRedisTemplate template;

        KeyspaceEventsEnsurer(StringRedisTemplate template) {
            this.template = template;
        }

        public void ensureKeyspaceEvents() {
            try {
                java.util.Properties cfg = template.execute((RedisCallback<java.util.Properties>) conn ->
                        conn.serverCommands().getConfig("notify-keyspace-events"));
                String cur = cfg == null ? null : cfg.getProperty("notify-keyspace-events");
                Set<String> flags = new LinkedHashSet<>();
                if (cur != null && !cur.isBlank()) {
                    for (char c : cur.toCharArray()) {
                        flags.add(String.valueOf(c));
                    }
                }
                flags.add("E");
                flags.add("x");
                String merged = String.join("", flags);
                if (!merged.equals(cur)) {
                    template.execute((RedisCallback<Void>) conn -> {
                        conn.serverCommands().setConfig("notify-keyspace-events", merged);
                        return null;
                    });
                    Logs.info(Logs.Module.CPE, "Redis 过期事件已开启", "notify-keyspace-events", merged);
                }
            } catch (Exception e) {
                Logs.warn(Logs.Module.CPE, "开启 Redis 过期事件失败（会话到期收尾退化为 sweep 兜底）",
                        "原因", e.getMessage());
            }
        }
    }
}
