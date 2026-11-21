package com.Url_Shortener.cache;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

/**
 * ============================================================
 * POLYGLOT PERSISTENCE: Redis Cache Value DTO
 * ============================================================
 *
 * Stores a snapshot of URL mapping data in Redis for fast
 * redirect lookups. This is intentionally a minimal, flat DTO
 * to keep the Redis value compact and serialization efficient.
 *
 * Redis key: shortUrl (String)
 * Redis value: JSON serialization of this DTO
 * TTL: configurable via app.cache.redis.ttl-hours
 */
public class CachedUrlData implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String shortUrl;
    private final String originalUrl;
    private final Long ownerId;
    private final String ownerUsername;
    private final Integer clickCount;
    private final String createdDate;
    private final String updatedDate;
    private final String expiresAt;

    @JsonCreator
    public CachedUrlData(
            @JsonProperty("shortUrl") String shortUrl,
            @JsonProperty("originalUrl") String originalUrl,
            @JsonProperty("ownerId") Long ownerId,
            @JsonProperty("ownerUsername") String ownerUsername,
            @JsonProperty("clickCount") Integer clickCount,
            @JsonProperty("createdDate") String createdDate,
            @JsonProperty("updatedDate") String updatedDate,
            @JsonProperty("expiresAt") String expiresAt) {
        this.shortUrl = shortUrl;
        this.originalUrl = originalUrl;
        this.ownerId = ownerId;
        this.ownerUsername = ownerUsername;
        this.clickCount = clickCount;
        this.createdDate = createdDate;
        this.updatedDate = updatedDate;
        this.expiresAt = expiresAt;
    }

    public String getShortUrl() {
        return shortUrl;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getOwnerUsername() {
        return ownerUsername;
    }

    public Integer getClickCount() {
        return clickCount;
    }

    public String getCreatedDate() {
        return createdDate;
    }

    public String getUpdatedDate() {
        return updatedDate;
    }

    /** Stored link expiry; null for entries cached before it existed, or links without one. */
    public String getExpiresAt() {
        return expiresAt;
    }

    /**
     * Factory method to create a CachedUrlData from a DynamoDB UrlMappingItem.
     */
    public static CachedUrlData from(com.Url_Shortener.model.UrlMappingItem item) {
        return new CachedUrlData(
                item.getShortUrl(),
                item.getOriginalUrl(),
                item.getOwnerId(),
                item.getOwnerUsername(),
                item.getClickCount(),
                item.getCreatedDate(),
                item.getUpdatedDate(),
                item.getExpiresAt()
        );
    }

    @Override
    public String toString() {
        return "CachedUrlData{" +
                "shortUrl='" + shortUrl + '\'' +
                ", originalUrl='" + originalUrl + '\'' +
                ", ownerId=" + ownerId +
                '}';
    }
}
