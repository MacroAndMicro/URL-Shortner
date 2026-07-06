package com.Url_Shortener.dto;

import java.util.List;

public class DashboardDto {
    private long totalUrls;
    private long totalClicks;
    private long clicksToday;
    private long clicksThisWeek;
    private List<UrlMappingDto> topUrls;

    public DashboardDto() {
    }

    public DashboardDto(long totalUrls, long totalClicks, long clicksToday, long clicksThisWeek, List<UrlMappingDto> topUrls) {
        this.totalUrls = totalUrls;
        this.totalClicks = totalClicks;
        this.clicksToday = clicksToday;
        this.clicksThisWeek = clicksThisWeek;
        this.topUrls = topUrls;
    }

    public long getTotalUrls() {
        return totalUrls;
    }

    public void setTotalUrls(long totalUrls) {
        this.totalUrls = totalUrls;
    }

    public long getTotalClicks() {
        return totalClicks;
    }

    public void setTotalClicks(long totalClicks) {
        this.totalClicks = totalClicks;
    }

    public long getClicksToday() {
        return clicksToday;
    }

    public void setClicksToday(long clicksToday) {
        this.clicksToday = clicksToday;
    }

    public long getClicksThisWeek() {
        return clicksThisWeek;
    }

    public void setClicksThisWeek(long clicksThisWeek) {
        this.clicksThisWeek = clicksThisWeek;
    }

    public List<UrlMappingDto> getTopUrls() {
        return topUrls;
    }

    public void setTopUrls(List<UrlMappingDto> topUrls) {
        this.topUrls = topUrls;
    }
}
