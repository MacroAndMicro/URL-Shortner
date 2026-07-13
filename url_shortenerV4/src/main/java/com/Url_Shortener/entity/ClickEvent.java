package com.Url_Shortener.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

// ============================================================
// OLD POSTGRES IMPLEMENTATION — Commented out for MongoDB migration
// ============================================================
// This JPA entity has been replaced by:
//   - ClickEventDocument (MongoDB document)
//   - AnalyticsStorage interface (business abstraction)
//   - MongoAnalyticsStorage (implementation)
// Kept for reference.
// ============================================================

//@Entity
//@Table(name = "click_event")
public class ClickEvent {
    //@Id
    //@GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private LocalDateTime clickDate;
    private String ipAddress;
    //@Column(columnDefinition = "TEXT")
    private String referrer;
    //@Column(columnDefinition = "TEXT")
    private String userAgent;

    // ============================================================
    // POLYGLOT PERSISTENCE MIGRATION:
    // Changed from @ManyToOne UrlMapping to String shortUrl because
    // URL mappings are now stored in DynamoDB, not PostgreSQL.
    // The shortUrl field acts as the cross-database join key.
    // ============================================================
    //@Column(name = "short_url")
    private String shortUrl;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDateTime getClickDate() {
        return clickDate;
    }

    public void setClickDate(LocalDateTime clickDate) {
        this.clickDate = clickDate;
    }

    // OLD POSTGRES IMPLEMENTATION
    // public UrlMapping getUrlMapping() { return urlMapping; }
    // public void setUrlMapping(UrlMapping urlMapping) { this.urlMapping = urlMapping; }

    // NEW DYNAMODB IMPLEMENTATION — using shortUrl as cross-database key
    public String getShortUrl() {
        return shortUrl;
    }

    public void setShortUrl(String shortUrl) {
        this.shortUrl = shortUrl;
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
