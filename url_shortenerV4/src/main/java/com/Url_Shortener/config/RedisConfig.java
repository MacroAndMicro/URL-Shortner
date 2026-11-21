package com.Url_Shortener.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.ClientOptions;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * ============================================================
 * POLYGLOT PERSISTENCE: Redis Configuration
 * ============================================================
 *
 * Provides a StringRedisTemplate for manual JSON serialization.
 * We avoid complex Redis serializers in favor of explicit
 * ObjectMapper-based serialize/deserialize in the service layer.
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * Fails commands immediately while disconnected instead of queueing them
     * until the timeout, so a Redis outage does not slow down redirects.
     */
    @Bean
    public LettuceClientOptionsBuilderCustomizer failFastWhenDisconnected() {
        return options -> options.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }

    @Bean
    public ObjectMapper redisObjectMapper() {
        return new ObjectMapper();
    }
}
