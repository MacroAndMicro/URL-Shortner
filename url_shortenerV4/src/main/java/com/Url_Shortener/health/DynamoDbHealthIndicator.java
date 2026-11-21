package com.Url_Shortener.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Component("dynamoDb")
@ConditionalOnProperty(name = "dynamodb.enabled", havingValue = "true", matchIfMissing = true)
public class DynamoDbHealthIndicator implements HealthIndicator {

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public DynamoDbHealthIndicator(DynamoDbClient dynamoDbClient,
                                   @Value("${dynamodb.table-name:url_mappings}") String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    @Override
    public Health health() {
        try {
            String status = dynamoDbClient.describeTable(r -> r.tableName(tableName))
                    .table().tableStatusAsString();
            return Health.up().withDetail("table", tableName).withDetail("status", status).build();
        } catch (Exception e) {
            return Health.down(e).withDetail("table", tableName).build();
        }
    }
}
