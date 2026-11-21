package com.Url_Shortener.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageCursorTest {

    @Test
    void roundTripsAKey() {
        Map<String, String> key = Map.of(
                "createdDate", "2026-11-02T10:15:30.123456",
                "shortUrl", "QN7XOa0a");

        assertThat(PageCursor.decode(PageCursor.encode(key))).isEqualTo(key);
    }

    @Test
    void noKeyMeansNoCursor() {
        assertThat(PageCursor.encode(null)).isNull();
        assertThat(PageCursor.encode(Map.of())).isNull();
        assertThat(PageCursor.decode(null)).isNull();
        assertThat(PageCursor.decode(" ")).isNull();
    }

    @Test
    void rejectsMalformedCursors() {
        assertThatThrownBy(() -> PageCursor.decode("not base64!"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageCursor.decode("bm8tZXF1YWxz")) // "no-equals"
                .isInstanceOf(IllegalArgumentException.class);
    }
}
