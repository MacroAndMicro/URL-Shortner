package com.Url_Shortener.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Bounds how long a MongoDB call can wait. The driver's default is 30 s to
 * find a server, which made analytics pages hang while MongoDB was down.
 * Applied in code so it holds whatever SPRING_MONGODB_URI says.
 */
@Configuration
public class MongoTimeoutConfig {

    @Bean
    public MongoClientSettingsBuilderCustomizer mongoTimeouts(
            @Value("${app.mongodb.server-selection-timeout:2s}") Duration serverSelection,
            @Value("${app.mongodb.connect-timeout:2s}") Duration connect,
            @Value("${app.mongodb.read-timeout:5s}") Duration read) {
        return settings -> settings
                .applyToClusterSettings(c -> c.serverSelectionTimeout(serverSelection.toMillis(), TimeUnit.MILLISECONDS))
                .applyToSocketSettings(s -> s
                        .connectTimeout((int) connect.toMillis(), TimeUnit.MILLISECONDS)
                        .readTimeout((int) read.toMillis(), TimeUnit.MILLISECONDS));
    }
}
