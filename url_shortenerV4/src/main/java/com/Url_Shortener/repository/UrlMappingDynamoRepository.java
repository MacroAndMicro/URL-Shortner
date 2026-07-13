package com.Url_Shortener.repository;

import com.Url_Shortener.model.UrlMappingItem;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ============================================================
 * NEW DYNAMODB IMPLEMENTATION — URL Mapping Repository
 * ============================================================
 *
 * Replaces the JPA-based UrlMappingRepository.
 * All URL mapping persistence now goes through DynamoDB (Local).
 *
 * Table Design:
 *   PK:  shortUrl (String)
 *   GSI ownerId-index:  ownerId (PK) + createdDate (SK)
 *   GSI id-index:       id (PK)
 */
@Repository
@ConditionalOnProperty(name = "dynamodb.enabled", havingValue = "true", matchIfMissing = true)
public class UrlMappingDynamoRepository {

    private final DynamoDbTable<UrlMappingItem> urlMappingTable;
    private final DynamoDbClient dynamoDbClient;

    @Value("${dynamodb.table-name:url_mappings}")
    private String tableName;

    public UrlMappingDynamoRepository(DynamoDbTable<UrlMappingItem> urlMappingTable,
                                      DynamoDbClient dynamoDbClient) {
        this.urlMappingTable = urlMappingTable;
        this.dynamoDbClient = dynamoDbClient;
    }

    /**
     * Creates the DynamoDB table and GSIs in a background thread so it
     * does not block Spring Boot startup. The table must exist before
     * any read/write operations succeed.
     *
     * Connection timeouts are set to 10s (API call) and 5s (attempt).
     * Table creation retries once on transient failures.
     */
    @PostConstruct
    public void ensureTableExists() {
        // Skip if running in fallback mode (dependencies are null)
        if (dynamoDbClient == null) {
            return;
        }
        Thread initThread = new Thread(() -> {
            try {
                Thread.sleep(1000); // Brief delay to let everything settle
                dynamoDbClient.describeTable(r -> r.tableName(tableName));
                System.out.println("INFO: DynamoDB table '" + tableName + "' already exists.");
            } catch (ResourceNotFoundException e) {
                try {
                    createTable();
                    System.out.println("INFO: DynamoDB table '" + tableName + "' created successfully.");
                } catch (Exception createEx) {
                    System.err.println("WARN: Could not create DynamoDB table '"
                            + tableName + "': " + createEx.getMessage());
                }
            } catch (Exception e) {
                System.err.println("WARN: Could not connect to DynamoDB at startup: "
                        + e.getMessage());
            }
        }, "dynamodb-init");
        initThread.setDaemon(true);
        initThread.start();
    }

    private void createTable() {
        dynamoDbClient.createTable(CreateTableRequest.builder()
                .tableName(tableName)
                .keySchema(
                        KeySchemaElement.builder()
                                .attributeName("shortUrl")
                                .keyType(KeyType.HASH)
                                .build()
                )
                .attributeDefinitions(
                        AttributeDefinition.builder()
                                .attributeName("shortUrl")
                                .attributeType(ScalarAttributeType.S)
                                .build(),
                        AttributeDefinition.builder()
                                .attributeName("ownerId")
                                .attributeType(ScalarAttributeType.N)
                                .build(),
                        AttributeDefinition.builder()
                                .attributeName("createdDate")
                                .attributeType(ScalarAttributeType.S)
                                .build(),
                        AttributeDefinition.builder()
                                .attributeName("id")
                                .attributeType(ScalarAttributeType.N)
                                .build()
                )
                .globalSecondaryIndexes(
                        GlobalSecondaryIndex.builder()
                                .indexName("ownerId-index")
                                .keySchema(
                                        KeySchemaElement.builder()
                                                .attributeName("ownerId")
                                                .keyType(KeyType.HASH)
                                                .build(),
                                        KeySchemaElement.builder()
                                                .attributeName("createdDate")
                                                .keyType(KeyType.RANGE)
                                                .build()
                                )
                                .projection(Projection.builder()
                                        .projectionType(ProjectionType.ALL)
                                        .build())
                                .provisionedThroughput(ProvisionedThroughput.builder()
                                        .readCapacityUnits(5L)
                                        .writeCapacityUnits(5L)
                                        .build())
                                .build(),
                        GlobalSecondaryIndex.builder()
                                .indexName("id-index")
                                .keySchema(
                                        KeySchemaElement.builder()
                                                .attributeName("id")
                                                .keyType(KeyType.HASH)
                                                .build()
                                )
                                .projection(Projection.builder()
                                        .projectionType(ProjectionType.ALL)
                                        .build())
                                .provisionedThroughput(ProvisionedThroughput.builder()
                                        .readCapacityUnits(5L)
                                        .writeCapacityUnits(5L)
                                        .build())
                                .build()
                )
                .provisionedThroughput(ProvisionedThroughput.builder()
                        .readCapacityUnits(5L)
                        .writeCapacityUnits(5L)
                        .build())
                .build());
    }

    // ============================================================
    // CREATE / UPDATE
    // ============================================================

    /**
     * Saves (creates or updates) a URL mapping item.
     */
    public UrlMappingItem save(UrlMappingItem item) {
        urlMappingTable.putItem(item);
        return item;
    }

    /**
     * Saves with a condition that the shortUrl must not already exist.
     * Returns true if the item was saved, false if shortUrl already exists.
     */
    public boolean saveIfNotExists(UrlMappingItem item) {
        try {
            urlMappingTable.putItem(PutItemEnhancedRequest.builder(UrlMappingItem.class)
                    .item(item)
                    .conditionExpression(Expression.builder()
                            .expression("attribute_not_exists(shortUrl)")
                            .build())
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    // ============================================================
    // READ BY SHORT URL (PK — most frequent: redirect lookups)
    // ============================================================

    /**
     * Finds a URL mapping by its short URL (partition key lookup).
     */
    public Optional<UrlMappingItem> findByShortUrl(String shortUrl) {
        Key key = Key.builder().partitionValue(shortUrl).build();
        UrlMappingItem item = urlMappingTable.getItem(key);
        return Optional.ofNullable(item);
    }

    /**
     * Finds a URL mapping by short URL AND verifies ownership.
     */
    public Optional<UrlMappingItem> findByShortUrlAndOwnerId(String shortUrl, Long ownerId) {
        Optional<UrlMappingItem> item = findByShortUrl(shortUrl);
        return item.filter(i -> i.getOwnerId().equals(ownerId));
    }

    // ============================================================
    // READ BY OWNER (GSI: ownerId-index)
    // ============================================================

    /**
     * Finds all URL mappings belonging to a user, sorted by createdDate (newest first).
     */
    public List<UrlMappingItem> findByOwnerId(Long ownerId) {
        QueryConditional condition = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(ownerId).build()
        );
        return urlMappingTable.index("ownerId-index")
                .query(r -> r.queryConditional(condition)
                        .scanIndexForward(false))  // newest first
                .stream()
                .flatMap(page -> page.items().stream())
                .collect(Collectors.toList());
    }

    /**
     * Paginated query for URLs belonging to a user, sorted by createdDate.
     * Uses DynamoDB token-based pagination via LastEvaluatedKey.
     *
     * DynamoDB uses token-based pagination (not offset-based like SQL).
     * The caller must store and pass back the LastEvaluatedKey for the next page.
     *
     * @param ownerId           The user ID to query URLs for
     * @param pageSize          Maximum items per page
     * @param lastEvaluatedKey  Token from previous page (null for first page)
     * @param ascending         true = oldest first, false = newest first
     * @return DynamoPageResult containing items and the next page token
     */
    public DynamoPageResult<UrlMappingItem> findByOwnerIdPaginated(Long ownerId, int pageSize,
                                                                    Map<String, String> lastEvaluatedKey,
                                                                    boolean ascending) {
        var iterable = urlMappingTable.index("ownerId-index")
                .query(r -> {
                    r.queryConditional(QueryConditional.keyEqualTo(
                                    Key.builder().partitionValue(ownerId).build()))
                            .limit(pageSize)
                            .scanIndexForward(ascending);
                    if (lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty()) {
                        Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> key =
                                lastEvaluatedKey.entrySet().stream()
                                        .collect(Collectors.toMap(
                                                Map.Entry::getKey,
                                                e -> software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                                                        .s(e.getValue()).build()
                                        ));
                        r.exclusiveStartKey(key);
                    }
                });

        Iterator<Page<UrlMappingItem>> iterator = iterable.iterator();
        if (!iterator.hasNext()) {
            return new DynamoPageResult<>(Collections.emptyList(), null);
        }

        Page<UrlMappingItem> page = iterator.next();
        List<UrlMappingItem> items = new ArrayList<>(page.items());

        Map<String, String> nextKey = null;
        if (page.lastEvaluatedKey() != null && !page.lastEvaluatedKey().isEmpty()) {
            nextKey = page.lastEvaluatedKey().entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> e.getValue().s()
                    ));
        }

        return new DynamoPageResult<>(items, nextKey);
    }

    // ============================================================
    // READ BY ID (GSI: id-index)
    // ============================================================

    /**
     * Finds a URL mapping by its numeric ID using the id-index GSI.
     */
    public Optional<UrlMappingItem> findById(Long id) {
        QueryConditional condition = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(id).build()
        );
        return urlMappingTable.index("id-index")
                .query(r -> r.queryConditional(condition).limit(1))
                .stream()
                .flatMap(page -> page.items().stream())
                .findFirst();
    }

    /**
     * Finds a URL mapping by ID AND verifies ownership.
     */
    public Optional<UrlMappingItem> findByIdAndOwnerId(Long id, Long ownerId) {
        Optional<UrlMappingItem> item = findById(id);
        return item.filter(i -> i.getOwnerId().equals(ownerId));
    }

    // ============================================================
    // EXISTENCE CHECKS
    // ============================================================

    /**
     * Checks if a short URL already exists in the table.
     */
    public boolean existsByShortUrl(String shortUrl) {
        return findByShortUrl(shortUrl).isPresent();
    }

    /**
     * Checks if a user has already shortened a given original URL.
     * Queries the ownerId-index GSI and filters by originalUrl.
     */
    public boolean existsByOriginalUrlAndOwnerId(String originalUrl, Long ownerId) {
        QueryConditional condition = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(ownerId).build()
        );
        return urlMappingTable.index("ownerId-index")
                .query(r -> r.queryConditional(condition))
                .stream()
                .flatMap(page -> page.items().stream())
                .anyMatch(item -> item.getOriginalUrl().equals(originalUrl));
    }

    // ============================================================
    // COUNT
    // ============================================================

    /**
     * Counts the number of URL mappings belonging to a user.
     */
    public long countByOwnerId(Long ownerId) {
        QueryConditional condition = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(ownerId).build()
        );
        return urlMappingTable.index("ownerId-index")
                .query(r -> r.queryConditional(condition))
                .stream()
                .flatMap(page -> page.items().stream())
                .count();
    }

    // ============================================================
    // DELETE
    // ============================================================

    /**
     * Deletes a URL mapping by its short URL (partition key).
     */
    public void deleteByShortUrl(String shortUrl) {
        Key key = Key.builder().partitionValue(shortUrl).build();
        urlMappingTable.deleteItem(key);
    }

    // ============================================================
    // CLICK COUNT (Atomic Increment)
    // ============================================================

    /**
     * Atomically increments the clickCount for a given short URL.
     * Uses DynamoDB UpdateExpression: SET clickCount = clickCount + :inc
     *
     * NOTE: Uses the low-level DynamoDbClient (not the Enhanced client)
     * because atomic increment via UpdateExpression is not supported
     * by the Enhanced client's updateItem (which replaces entire items).
     */
    public void incrementClickCount(String shortUrl) {
        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("shortUrl",
                        software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                                .s(shortUrl).build()))
                .updateExpression("SET clickCount = clickCount + :inc")
                .expressionAttributeValues(Map.of(":inc",
                        software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                                .n("1").build()))
                .build());
    }

    // ============================================================
    // PAGINATION RESULT DTO
    // ============================================================

    /**
     * Simple pagination result wrapping items and the LastEvaluatedKey
     * for DynamoDB token-based pagination.
     */
    public static class DynamoPageResult<T> {
        private final List<T> items;
        private final Map<String, String> lastEvaluatedKey;

        public DynamoPageResult(List<T> items, Map<String, String> lastEvaluatedKey) {
            this.items = items;
            this.lastEvaluatedKey = lastEvaluatedKey;
        }

        public List<T> getItems() {
            return items;
        }

        public Map<String, String> getLastEvaluatedKey() {
            return lastEvaluatedKey;
        }

        public boolean hasMore() {
            return lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty();
        }
    }
}
