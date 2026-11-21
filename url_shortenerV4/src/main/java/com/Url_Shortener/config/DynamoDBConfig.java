package com.Url_Shortener.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import com.Url_Shortener.model.UrlMappingItem;

import java.net.URI;
import java.time.Duration;

/**
 * ============================================================
 * POLYGLOT PERSISTENCE: DynamoDB Configuration
 * ============================================================
 *
 * Configures DynamoDB Local (Docker) client.
 * Uses dummy credentials — DynamoDB Local requires them but does not validate.
 * Endpoint comes from environment variables for flexibility.
 */
@Configuration
public class DynamoDBConfig {

    @Value("${dynamodb.endpoint:http://localhost:8000}")
    private String dynamoDbEndpoint;

    @Value("${dynamodb.region:us-east-1}")
    private String dynamoDbRegion;

    @Value("${dynamodb.table-name:url_mappings}")
    private String tableName;

    @Bean
    public DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder()
                .endpointOverride(URI.create(dynamoDbEndpoint))
                .region(Region.of(dynamoDbRegion))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("dummy", "dummy")
                ))
                // Bounds how long a redirect can wait when DynamoDB is unreachable.
                .overrideConfiguration(config -> config
                        .apiCallAttemptTimeout(Duration.ofSeconds(2))
                        .apiCallTimeout(Duration.ofSeconds(3)))
                .build();
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
    }

    @Bean
    public DynamoDbTable<UrlMappingItem> urlMappingTable(DynamoDbEnhancedClient enhancedClient) {
        return enhancedClient.table(tableName,
                TableSchema.fromBean(UrlMappingItem.class));
    }
}
