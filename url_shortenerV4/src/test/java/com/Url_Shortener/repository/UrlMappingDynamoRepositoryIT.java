package com.Url_Shortener.repository;

import com.Url_Shortener.model.UrlMappingItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers upgrading a table created before ownerUrl-index existed, which is
 * the state of any DynamoDB volume from earlier versions of the app.
 */
@Testcontainers
class UrlMappingDynamoRepositoryIT {

    @Container
    static final GenericContainer<?> DYNAMODB = new GenericContainer<>("amazon/dynamodb-local:2.5.4")
            .withExposedPorts(8000);

    private DynamoDbClient client;
    private UrlMappingDynamoRepository repository;
    private String tableName;

    @BeforeEach
    void setUp() {
        client = DynamoDbClient.builder()
                .endpointOverride(URI.create("http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000)))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("dummy", "dummy")))
                .build();
        tableName = "url_mappings_" + UUID.randomUUID().toString().substring(0, 8);
        var table = DynamoDbEnhancedClient.builder().dynamoDbClient(client).build()
                .table(tableName, TableSchema.fromBean(UrlMappingItem.class));
        repository = new UrlMappingDynamoRepository(table, client);
        ReflectionTestUtils.setField(repository, "tableName", tableName);
    }

    @Test
    void upgradesAnOldTableAndFindsItemsWrittenBeforeTheUpgrade() {
        createTableWithoutOwnerUrlIndex();
        putLegacyItem("legacy0001", 7L, "https://example.com/old");
        putLegacyItem("legacy0002", 8L, "https://example.com/old");

        assertThat(repository.tryEnsureTableExists()).isTrue();

        assertThat(repository.findByOwnerAndOriginalUrl(7L, "https://example.com/old"))
                .map(UrlMappingItem::getShortUrl).contains("legacy0001");
        assertThat(repository.findByOwnerAndOriginalUrl(8L, "https://example.com/old"))
                .map(UrlMappingItem::getShortUrl).contains("legacy0002");
        assertThat(repository.findByOwnerAndOriginalUrl(7L, "https://example.com/other")).isEmpty();

        // Running again on an already upgraded table is a no-op.
        assertThat(repository.tryEnsureTableExists()).isTrue();
    }

    @Test
    void newTableHasTheIndexAndEditsMoveTheLookupKey() {
        assertThat(repository.tryEnsureTableExists()).isTrue();

        UrlMappingItem item = new UrlMappingItem();
        item.setShortUrl("abc1234567");
        item.setId(1L);
        item.setOwnerId(7L);
        item.setOriginalUrl("https://example.com/a");
        item.setOwnerUrlKey(UrlMappingItem.ownerUrlKey(7L, "https://example.com/a"));
        item.setCreatedDate("2026-11-21T10:00:00");
        assertThat(repository.saveIfNotExists(item)).isTrue();

        assertThat(repository.findByOwnerAndOriginalUrl(7L, "https://example.com/a")).isPresent();

        repository.updateOriginalUrl("abc1234567", "https://example.com/b",
                UrlMappingItem.ownerUrlKey(7L, "https://example.com/b"), "2026-11-21T11:00:00");

        assertThat(repository.findByOwnerAndOriginalUrl(7L, "https://example.com/a")).isEmpty();
        assertThat(repository.findByOwnerAndOriginalUrl(7L, "https://example.com/b")).isPresent();
    }

    private void createTableWithoutOwnerUrlIndex() {
        client.createTable(r -> r.tableName(tableName)
                .keySchema(KeySchemaElement.builder().attributeName("shortUrl").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName("shortUrl").attributeType(ScalarAttributeType.S).build())
                .provisionedThroughput(ProvisionedThroughput.builder()
                        .readCapacityUnits(5L).writeCapacityUnits(5L).build()));
    }

    private void putLegacyItem(String shortUrl, Long ownerId, String originalUrl) {
        client.putItem(r -> r.tableName(tableName).item(Map.of(
                "shortUrl", AttributeValue.builder().s(shortUrl).build(),
                "id", AttributeValue.builder().n(String.valueOf(Math.abs(shortUrl.hashCode()))).build(),
                "ownerId", AttributeValue.builder().n(ownerId.toString()).build(),
                "originalUrl", AttributeValue.builder().s(originalUrl).build(),
                "createdDate", AttributeValue.builder().s("2026-01-01T00:00:00").build())));
    }
}
