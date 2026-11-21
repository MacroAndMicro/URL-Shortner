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
 *   GSI ownerUrl-index: ownerUrlKey (PK), keys only
 */
@Repository
@ConditionalOnProperty(name = "dynamodb.enabled", havingValue = "true", matchIfMissing = true)
public class UrlMappingDynamoRepository {

    static final String OWNER_URL_INDEX = "ownerUrl-index";

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
     * Retries until it succeeds, so a slow start or a DynamoDB outage
     * at startup cannot leave the application without its table.
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
                while (!tryEnsureTableExists()) {
                    Thread.sleep(5000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "dynamodb-init");
        initThread.setDaemon(true);
        initThread.start();
    }

    boolean tryEnsureTableExists() {
        try {
            TableDescription table = dynamoDbClient.describeTable(r -> r.tableName(tableName)).table();
            System.out.println("INFO: DynamoDB table '" + tableName + "' already exists.");
            ensureOwnerUrlIndex(table);
            backfillOwnerUrlKeys();
            return true;
        } catch (ResourceNotFoundException e) {
            try {
                createTable();
                System.out.println("INFO: DynamoDB table '" + tableName + "' created successfully.");
                return true;
            } catch (ResourceInUseException inUse) {
                // Another application instance created it first.
                return true;
            } catch (Exception createEx) {
                System.err.println("WARN: Could not create DynamoDB table '"
                        + tableName + "', will retry: " + createEx.getMessage());
                return false;
            }
        } catch (Exception e) {
            System.err.println("WARN: Could not reach DynamoDB at startup, will retry: " + e.getMessage());
            return false;
        }
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
                                .build(),
                        ownerUrlKeyDefinition()
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
                                .build(),
                        ownerUrlIndex()
                )
                .provisionedThroughput(ProvisionedThroughput.builder()
                        .readCapacityUnits(5L)
                        .writeCapacityUnits(5L)
                        .build())
                .build());
    }

    private static AttributeDefinition ownerUrlKeyDefinition() {
        return AttributeDefinition.builder()
                .attributeName("ownerUrlKey")
                .attributeType(ScalarAttributeType.S)
                .build();
    }

    private static GlobalSecondaryIndex ownerUrlIndex() {
        return GlobalSecondaryIndex.builder()
                .indexName(OWNER_URL_INDEX)
                .keySchema(KeySchemaElement.builder()
                        .attributeName("ownerUrlKey")
                        .keyType(KeyType.HASH)
                        .build())
                // Only the short code is needed; the full item is then read by key.
                .projection(Projection.builder()
                        .projectionType(ProjectionType.KEYS_ONLY)
                        .build())
                .provisionedThroughput(ProvisionedThroughput.builder()
                        .readCapacityUnits(5L)
                        .writeCapacityUnits(5L)
                        .build())
                .build();
    }

    /**
     * Adds ownerUrl-index to a table created before the index existed.
     */
    private void ensureOwnerUrlIndex(TableDescription table) {
        boolean present = table.hasGlobalSecondaryIndexes() && table.globalSecondaryIndexes().stream()
                .anyMatch(gsi -> OWNER_URL_INDEX.equals(gsi.indexName()));
        if (present) {
            return;
        }
        GlobalSecondaryIndex gsi = ownerUrlIndex();
        dynamoDbClient.updateTable(UpdateTableRequest.builder()
                .tableName(tableName)
                .attributeDefinitions(ownerUrlKeyDefinition())
                .globalSecondaryIndexUpdates(GlobalSecondaryIndexUpdate.builder()
                        .create(CreateGlobalSecondaryIndexAction.builder()
                                .indexName(gsi.indexName())
                                .keySchema(gsi.keySchema())
                                .projection(gsi.projection())
                                .provisionedThroughput(gsi.provisionedThroughput())
                                .build())
                        .build())
                .build());
        System.out.println("INFO: Added index '" + OWNER_URL_INDEX + "' to DynamoDB table '" + tableName + "'.");
    }

    /**
     * Sets ownerUrlKey on items written before the attribute existed, so the
     * duplicate check also finds them. Safe to run repeatedly: items that
     * already have the key are skipped.
     */
    private void backfillOwnerUrlKeys() {
        int updated = 0;
        Map<String, AttributeValue> startKey = null;
        do {
            ScanResponse page = dynamoDbClient.scan(ScanRequest.builder()
                    .tableName(tableName)
                    .filterExpression("attribute_not_exists(ownerUrlKey) "
                            + "AND attribute_exists(ownerId) AND attribute_exists(originalUrl)")
                    .projectionExpression("shortUrl, ownerId, originalUrl")
                    .exclusiveStartKey(startKey)
                    .build());
            for (Map<String, AttributeValue> row : page.items()) {
                String key = UrlMappingItem.ownerUrlKey(
                        Long.valueOf(row.get("ownerId").n()), row.get("originalUrl").s());
                try {
                    dynamoDbClient.updateItem(UpdateItemRequest.builder()
                            .tableName(tableName)
                            .key(Map.of("shortUrl", row.get("shortUrl")))
                            .updateExpression("SET ownerUrlKey = :k")
                            // Skip items deleted or edited since the scan.
                            .conditionExpression("attribute_exists(shortUrl) AND originalUrl = :u")
                            .expressionAttributeValues(Map.of(
                                    ":k", AttributeValue.builder().s(key).build(),
                                    ":u", row.get("originalUrl")))
                            .build());
                    updated++;
                } catch (ConditionalCheckFailedException ignored) {
                    // Changed since the scan; a later write sets the key itself.
                }
            }
            startKey = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty()
                    ? page.lastEvaluatedKey() : null;
        } while (startKey != null);
        if (updated > 0) {
            System.out.println("INFO: Set ownerUrlKey on " + updated + " existing URL mappings.");
        }
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
        // Guest links have no owner.
        return item.filter(i -> ownerId.equals(i.getOwnerId()));
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
     * @param createdAfter      only items created after this ISO date-time; null for all
     * @return DynamoPageResult containing items and the next page token
     */
    public DynamoPageResult<UrlMappingItem> findByOwnerIdPaginated(Long ownerId, int pageSize,
                                                                    Map<String, String> lastEvaluatedKey,
                                                                    boolean ascending,
                                                                    String createdAfter) {
        var iterable = urlMappingTable.index("ownerId-index")
                .query(r -> {
                    r.queryConditional(ownerCondition(ownerId, createdAfter))
                            .limit(pageSize)
                            .scanIndexForward(ascending);
                    if (lastEvaluatedKey != null && !lastEvaluatedKey.isEmpty()) {
                        r.exclusiveStartKey(toExclusiveStartKey(ownerId, lastEvaluatedKey));
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
            // Keep only the string attributes. ownerId is numeric and is
            // restored from the authenticated user on the next request.
            nextKey = page.lastEvaluatedKey().entrySet().stream()
                    .filter(e -> e.getValue().s() != null)
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> e.getValue().s()
                    ));
        }

        return new DynamoPageResult<>(items, nextKey);
    }

    /** ownerId-index condition, narrowed by the createdDate sort key when given. */
    private static QueryConditional ownerCondition(Long ownerId, String createdAfter) {
        if (createdAfter == null) {
            return QueryConditional.keyEqualTo(Key.builder().partitionValue(ownerId).build());
        }
        return QueryConditional.sortGreaterThan(
                Key.builder().partitionValue(ownerId).sortValue(createdAfter).build());
    }

    private Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> toExclusiveStartKey(
            Long ownerId, Map<String, String> lastEvaluatedKey) {
        String createdDate = lastEvaluatedKey.get("createdDate");
        String shortUrl = lastEvaluatedKey.get("shortUrl");
        if (createdDate == null || shortUrl == null) {
            throw new IllegalArgumentException("Invalid cursor");
        }
        return Map.of(
                "ownerId", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                        .n(ownerId.toString()).build(),
                "createdDate", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                        .s(createdDate).build(),
                "shortUrl", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                        .s(shortUrl).build());
    }

    // ============================================================
    // READ BY OWNER + URL (GSI: ownerUrl-index)
    // ============================================================

    /**
     * Finds the user's mappings for a URL with one index query plus a key
     * read per match, independent of how many URLs the user has. Usually
     * there is at most one; there can be more when an earlier link expired
     * and a new one was made, so the caller picks the one still active.
     *
     * The index is updated asynchronously, so a mapping created a moment ago
     * may not be found yet; the caller then creates a second link, which is
     * harmless.
     */
    public List<UrlMappingItem> findByOwnerAndOriginalUrl(Long ownerId, String originalUrl) {
        QueryConditional condition = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(UrlMappingItem.ownerUrlKey(ownerId, originalUrl)).build());
        return urlMappingTable.index(OWNER_URL_INDEX)
                .query(r -> r.queryConditional(condition))
                .stream()
                .flatMap(page -> page.items().stream())
                .map(keys -> findByShortUrl(keys.getShortUrl()))
                .flatMap(Optional::stream)
                // Guards against an index entry that lags an edit or delete.
                .filter(item -> ownerId.equals(item.getOwnerId())
                        && originalUrl.equals(item.getOriginalUrl()))
                .collect(Collectors.toList());
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
        // Guest links have no owner.
        return item.filter(i -> ownerId.equals(i.getOwnerId()));
    }

    // ============================================================
    // COUNT
    // ============================================================

    /**
     * Counts the number of URL mappings belonging to a user.
     */
    public long countByOwnerId(Long ownerId) {
        return countByOwnerId(ownerId, null);
    }

    /**
     * Counts a user's mappings created after createdAfter (all when null).
     * Asks DynamoDB for the count only, so no items are transferred.
     */
    public long countByOwnerId(Long ownerId, String createdAfter) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":owner", AttributeValue.builder().n(ownerId.toString()).build());
        String keyCondition = "ownerId = :owner";
        if (createdAfter != null) {
            values.put(":after", AttributeValue.builder().s(createdAfter).build());
            keyCondition += " AND createdDate > :after";
        }

        long total = 0;
        Map<String, AttributeValue> startKey = null;
        do {
            QueryResponse page = dynamoDbClient.query(QueryRequest.builder()
                    .tableName(tableName)
                    .indexName("ownerId-index")
                    .keyConditionExpression(keyCondition)
                    .expressionAttributeValues(values)
                    .select(Select.COUNT)
                    .exclusiveStartKey(startKey)
                    .build());
            total += page.count();
            startKey = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty()
                    ? page.lastEvaluatedKey() : null;
        } while (startKey != null);
        return total;
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
     * Does nothing if the mapping has been deleted, because a click event
     * can still be in flight when that happens.
     *
     * NOTE: Uses the low-level DynamoDbClient (not the Enhanced client)
     * because atomic increment via UpdateExpression is not supported
     * by the Enhanced client's updateItem (which replaces entire items).
     */
    public void incrementClickCount(String shortUrl) {
        try {
            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("shortUrl",
                            software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                                    .s(shortUrl).build()))
                    .updateExpression("ADD clickCount :inc")
                    .conditionExpression("attribute_exists(shortUrl)")
                    .expressionAttributeValues(Map.of(":inc",
                            software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                                    .n("1").build()))
                    .build());
        } catch (ConditionalCheckFailedException e) {
            // Mapping was deleted; there is no count left to update.
        }
    }

    /**
     * Updates only the original URL attributes, leaving clickCount untouched.
     *
     * @return true if updated, false if the mapping no longer exists
     */
    public boolean updateOriginalUrl(String shortUrl, String originalUrl, String ownerUrlKey,
                                     String updatedDate) {
        try {
            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("shortUrl",
                            software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                                    .s(shortUrl).build()))
                    .updateExpression("SET originalUrl = :originalUrl, ownerUrlKey = :ownerUrlKey, "
                            + "updatedDate = :updatedDate")
                    .conditionExpression("attribute_exists(shortUrl)")
                    .expressionAttributeValues(Map.of(
                            ":originalUrl", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                                    .s(originalUrl).build(),
                            ":ownerUrlKey", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                                    .s(ownerUrlKey).build(),
                            ":updatedDate", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                                    .s(updatedDate).build()))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
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
