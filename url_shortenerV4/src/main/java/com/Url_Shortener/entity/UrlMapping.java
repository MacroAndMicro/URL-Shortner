package com.Url_Shortener.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.List;

// ============================================================
// OLD POSTGRES IMPLEMENTATION — Commented out for DynamoDB migration
// ============================================================
// The JPA entity annotations below are commented out because URL mappings
// have been migrated to DynamoDB (see com.Url_Shortener.model.UrlMappingItem).
// This class is kept for reference and to avoid breaking other classes
// (like ClickEvent) that still reference UrlMapping as a type.
// ============================================================

//@Entity
//@Table(name = "url_mapping")
public class UrlMapping {
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
//    @Column(columnDefinition = "TEXT")
    private String originalUrl;
//    @Column(unique = true)
    private String shortUrl;
    private int clickCount = 0;
    private LocalDateTime createdDate;

//    @ManyToOne
//    @JoinColumn(name = "user_id")
    private User user;

//    @OneToMany(mappedBy = "urlMapping")
    private List<ClickEvent> clickEvents;

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

    public String getShortUrl() {
        return shortUrl;
    }

    public void setShortUrl(String shortUrl) {
        this.shortUrl = shortUrl;
    }

    public int getClickCount() {
        return clickCount;
    }

    public void setClickCount(int clickCount) {
        this.clickCount = clickCount;
    }

    public LocalDateTime getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDateTime createdDate) {
        this.createdDate = createdDate;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public List<ClickEvent> getClickEvents() {
        return clickEvents;
    }

    public void setClickEvents(List<ClickEvent> clickEvents) {
        this.clickEvents = clickEvents;
    }

}
