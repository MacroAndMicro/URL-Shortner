package com.Url_Shortener.model;

import org.springframework.data.annotation.Id;
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
public class ClickEventDocument {

    @Id
    private String id;

    @Field("short_url")
    private String shortUrl;

    private LocalDateTime clickDate;
    private String ipAddress;
    private String referrer;
    private String userAgent;

    public ClickEventDocument() {
    }

    public ClickEventDocument(String shortUrl, LocalDateTime clickDate, String ipAddress,
                              String referrer, String userAgent) {
        this.shortUrl = shortUrl;
        this.clickDate = clickDate;
        this.ipAddress = ipAddress;
        this.referrer = referrer;
        this.userAgent = userAgent;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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
}
