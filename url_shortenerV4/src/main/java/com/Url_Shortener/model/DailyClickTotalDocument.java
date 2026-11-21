package com.Url_Shortener.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Clicks on one link on one day. Kept for as long as the link exists, while
 * individual click events are trimmed by plan (see Plan.clickDetailRetention),
 * so charts of clicks per day keep working after the details are gone.
 *
 * Collection: daily_click_totals; _id is "shortUrl|yyyy-MM-dd" so each
 * click is an upsert on a known key.
 */
@Document(collection = "daily_click_totals")
@CompoundIndex(name = "short_url_day", def = "{'shortUrl': 1, 'day': 1}")
public class DailyClickTotalDocument {

    @Id
    private String id;
    private String shortUrl;
    /** yyyy-MM-dd in the server's time zone, so it sorts and compares as text. */
    private String day;
    private long count;

    public static String idOf(String shortUrl, String day) {
        return shortUrl + "|" + day;
    }

    public String getId() {
        return id;
    }

    public String getShortUrl() {
        return shortUrl;
    }

    public String getDay() {
        return day;
    }

    public long getCount() {
        return count;
    }
}
