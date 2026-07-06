package com.Url_Shortener.dto;

import java.time.LocalDateTime;

public class ClickEventDetailDto {
    private Long id;
    private LocalDateTime clickDate;
    private String ipAddress;
    private String referrer;
    private String userAgent;

    public ClickEventDetailDto() {
    }

    public ClickEventDetailDto(Long id, LocalDateTime clickDate, String ipAddress, String referrer, String userAgent) {
        this.id = id;
        this.clickDate = clickDate;
        this.ipAddress = ipAddress;
        this.referrer = referrer;
        this.userAgent = userAgent;
    }

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
