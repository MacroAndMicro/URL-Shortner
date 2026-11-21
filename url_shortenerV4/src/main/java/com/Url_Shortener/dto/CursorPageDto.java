package com.Url_Shortener.dto;

import java.util.List;

public class CursorPageDto<T> {
    private final List<T> content;
    private final String nextCursor;
    private final int size;
    private final long totalElements;

    public CursorPageDto(List<T> content, String nextCursor, int size, long totalElements) {
        this.content = content;
        this.nextCursor = nextCursor;
        this.size = size;
        this.totalElements = totalElements;
    }

    public List<T> getContent() {
        return content;
    }

    public String getNextCursor() {
        return nextCursor;
    }

    public int getSize() {
        return size;
    }

    public long getTotalElements() {
        return totalElements;
    }
}
