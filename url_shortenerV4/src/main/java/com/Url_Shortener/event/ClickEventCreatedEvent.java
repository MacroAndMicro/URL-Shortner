package com.Url_Shortener.event;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * ============================================================
 * KAFKA INTEGRATION: Click Event Created Event
 * ============================================================
 *
 * Kafka message representing a click/redirect event. This is
 * the contract between the producer (UrlMappingService) and
 * the consumer (KafkaAnalyticsConsumer).
 *
 * DESIGN PRINCIPLES:
 *   - Independent from persistence models (no @Document, @Entity)
 *   - Self-contained — all data needed by the consumer is here
 *   - Serializable for Kafka JSON serialization
 *   - Immutable after construction via constructor
 */
public class ClickEventCreatedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private String eventId;
    private String shortUrl;
    private String originalUrl;
    private Instant timestamp;
    private String ipAddress;
    private String referrer;
    private String userAgent;
    private Long ownerId;

    /**
     * Default constructor required for JSON deserialization.
     */
    public ClickEventCreatedEvent() {
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
    }

    /**
     * Creates a fully-populated event.
     */
    public ClickEventCreatedEvent(String shortUrl, String originalUrl,
                                   String ipAddress, String referrer,
                                   String userAgent, Long ownerId) {
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
        this.shortUrl = shortUrl;
        this.originalUrl = originalUrl;
        this.ipAddress = ipAddress;
        this.referrer = referrer;
        this.userAgent = userAgent;
        this.ownerId = ownerId;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getShortUrl() {
        return shortUrl;
    }

    public void setShortUrl(String shortUrl) {
        this.shortUrl = shortUrl;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public void setOriginalUrl(String originalUrl) {
        this.originalUrl = originalUrl;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getReferrer() {
        return referrer;
    }

    public void setReferrer(String referrer) {
        this.referrer = referrer;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    @Override
    public String toString() {
        return "ClickEventCreatedEvent{" +
                "eventId='" + eventId + '\'' +
                ", shortUrl='" + shortUrl + '\'' +
                ", originalUrl='" + originalUrl + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
