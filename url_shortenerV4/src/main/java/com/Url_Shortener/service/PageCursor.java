package com.Url_Shortener.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts a repository pagination key to and from the opaque cursor
 * string exposed by the API. Values must not contain '&'.
 */
final class PageCursor {

    private PageCursor() {
    }

    static String encode(Map<String, String> key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        String raw = key.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Map<String, String> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            Map<String, String> key = new LinkedHashMap<>();
            for (String pair : raw.split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length != 2 || parts[0].isEmpty()) {
                    throw new IllegalArgumentException("Invalid cursor");
                }
                key.put(parts[0], parts[1]);
            }
            return key;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cursor");
        }
    }
}
