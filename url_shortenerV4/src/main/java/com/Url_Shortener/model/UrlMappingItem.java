package com.Url_Shortener.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import com.Url_Shortener.security.Sha256;

/**
 * ============================================================
 * POLYGLOT PERSISTENCE: DynamoDB URL Mapping Model
 * ============================================================
 *
 * Replaces the JPA UrlMapping entity for URL mapping persistence.
 *
 * Table: url_mappings
 *   Partition Key: shortUrl (String)
 *
 * GSI: ownerId-index
 *   Partition Key: ownerId (Long)
 *   Sort Key:      createdDate (String - ISO 8601)
 *   Purpose:       List all URLs belonging to a user, sorted by creation date
 *
 * GSI: id-index
 *   Partition Key: id (Long)
 *   Purpose:       Lookup by numeric ID for REST API {id} endpoints
 *
 * GSI: ownerUrl-index
 *   Partition Key: ownerUrlKey (String - "ownerId#sha256(originalUrl)")
 *   Purpose:       Find whether a user already shortened a URL with one
 *                  small query, however many URLs they have. The URL is
 *                  hashed because index keys are size-limited and URLs
 *                  can be 2048 characters. Items without the attribute
 *                  (e.g. custom codes) are simply not in the index.
 */
@DynamoDbBean
public class UrlMappingItem {

    private String shortUrl;
    private Long id;
    private String originalUrl;
    private Long ownerId;
    private String ownerUsername;
    private Integer clickCount;
    private String createdDate;
    private String updatedDate;
    private String ownerUrlKey;
    private String expiresAt;

    public UrlMappingItem() {
    }

    /** The ownerUrl-index key for a user and a URL. */
    public static String ownerUrlKey(Long ownerId, String originalUrl) {
        return ownerId + "#" + Sha256.hex(originalUrl);
    }

    @DynamoDbPartitionKey
    public String getShortUrl() {
        return shortUrl;
    }

    public void setShortUrl(String shortUrl) {
        this.shortUrl = shortUrl;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = {"id-index"})
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public void setOriginalUrl(String originalUrl) {
        this.originalUrl = originalUrl;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = {"ownerId-index"})
    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getOwnerUsername() {
        return ownerUsername;
    }

    public void setOwnerUsername(String ownerUsername) {
        this.ownerUsername = ownerUsername;
    }

    public Integer getClickCount() {
        return clickCount;
    }

    public void setClickCount(Integer clickCount) {
        this.clickCount = clickCount;
    }

    @DynamoDbSecondarySortKey(indexNames = {"ownerId-index"})
    public String getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(String createdDate) {
        this.createdDate = createdDate;
    }

    public String getUpdatedDate() {
        return updatedDate;
    }

    public void setUpdatedDate(String updatedDate) {
        this.updatedDate = updatedDate;
    }

    /**
     * When the link stops working (ISO date-time), when known up front or
     * fixed by a plan change; null otherwise. See PlanService.linkExpiresAt.
     */
    public String getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = expiresAt;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = {"ownerUrl-index"})
    public String getOwnerUrlKey() {
        return ownerUrlKey;
    }

    public void setOwnerUrlKey(String ownerUrlKey) {
        this.ownerUrlKey = ownerUrlKey;
    }
}
