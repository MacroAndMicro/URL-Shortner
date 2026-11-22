package com.Url_Shortener.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

/**
 * ============================================================
 * MONGODB MIGRATION: Click Event Document
 * ============================================================
 *
 * Replaces the JPA ClickEvent entity for analytics persistence.
 * Stored in MongoDB collection "click_events".
 *
 * Fields preserve the original ClickEvent field names for consistency.
 * LocalDateTime is natively supported by Spring Data MongoDB (stored as ISODate).
 */
@Document(collection = "click_events")
@CompoundIndex(name = "short_url_click_date", def = "{'short_url': 1, 'clickDate': 1}")
public class ClickEventDocument {

    @Id
    private String id;

    // Sparse because documents written before this field was added have no eventId.
    @Indexed(name = "event_id_unique", unique = true, sparse = true)
    private String eventId;

    @Field("short_url")
    private String shortUrl;

    // Trimmed per plan by LinkCleanupJob; no TTL index, which would cut every plan at
    // the same age.
    private LocalDateTime clickDate;
    private String ipAddress;
    private String referrer;
    private String userAgent;

    /**
     * Whether this click has been added to its daily total. False from insert
     * until the total is updated, so a retry after a lost reply can finish the
     * job instead of skipping it as a duplicate. Null on clicks stored before
     * this field existed; those were counted.
     */
    private Boolean counted;

    public ClickEventDocument() {
    }

    public ClickEventDocument(String eventId, String shortUrl, LocalDateTime clickDate, String ipAddress,
                              String referrer, String userAgent) {
        this.eventId = eventId;
        this.shortUrl = shortUrl;
        this.clickDate = clickDate;
        this.ipAddress = ipAddress;
        this.referrer = referrer;
        this.userAgent = userAgent;
        this.counted = false;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public LocalDateTime getClickDate() {
        return clickDate;
    }

    public void setClickDate(LocalDateTime clickDate) {
        this.clickDate = clickDate;
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

    public Boolean getCounted() {
        return counted;
    }

    public void setCounted(Boolean counted) {
        this.counted = counted;
    }
}
