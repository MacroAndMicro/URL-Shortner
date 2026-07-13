package com.Url_Shortener.config;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Bean
    public ObjectMapper redisObjectMapper() {
        return new ObjectMapper();
    }
}
