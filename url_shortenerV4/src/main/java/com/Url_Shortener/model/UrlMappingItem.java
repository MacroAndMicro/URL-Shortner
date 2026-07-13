package com.Url_Shortener.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

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

    public UrlMappingItem() {
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
}
