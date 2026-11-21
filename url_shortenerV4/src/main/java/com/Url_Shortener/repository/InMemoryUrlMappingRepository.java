package com.Url_Shortener.repository;

import com.Url_Shortener.model.UrlMappingItem;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * ============================================================
 * IN-MEMORY FALLBACK — URL Mapping Repository
 * ============================================================
 *
 * Provides an in-memory implementation of URL mapping persistence
 * for development/testing when DynamoDB Local is unavailable.
 *
 * Enabled when: dynamodb.enabled=false
 * (Set this in application-local.yaml to use this fallback)
 */
@Repository
@ConditionalOnProperty(name = "dynamodb.enabled", havingValue = "false")
public class InMemoryUrlMappingRepository extends UrlMappingDynamoRepository {

    private final Map<String, UrlMappingItem> store = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(System.currentTimeMillis());

    public InMemoryUrlMappingRepository() {
        super(null, null);
    }

    @Override
    public void ensureTableExists() {
        // No-op for in-memory
    }

    @Override
    public UrlMappingItem save(UrlMappingItem item) {
        store.put(item.getShortUrl(), item);
        return item;
    }

    @Override
    public boolean saveIfNotExists(UrlMappingItem item) {
        return store.putIfAbsent(item.getShortUrl(), item) == null;
    }

    @Override
    public Optional<UrlMappingItem> findByShortUrl(String shortUrl) {
        return Optional.ofNullable(store.get(shortUrl));
    }

    @Override
    public Optional<UrlMappingItem> findByShortUrlAndOwnerId(String shortUrl, Long ownerId) {
        return findByShortUrl(shortUrl)
                .filter(i -> i.getOwnerId().equals(ownerId));
    }

    @Override
    public List<UrlMappingItem> findByOwnerId(Long ownerId) {
        return store.values().stream()
                .filter(i -> i.getOwnerId().equals(ownerId))
                .sorted((a, b) -> b.getCreatedDate().compareTo(a.getCreatedDate()))
                .collect(Collectors.toList());
    }

    @Override
    public DynamoPageResult<UrlMappingItem> findByOwnerIdPaginated(Long ownerId, int pageSize,
                                                                    Map<String, String> lastEvaluatedKey,
                                                                    boolean ascending) {
        List<UrlMappingItem> all = store.values().stream()
                .filter(i -> i.getOwnerId().equals(ownerId))
                .sorted((a, b) -> ascending
                        ? a.getCreatedDate().compareTo(b.getCreatedDate())
                        : b.getCreatedDate().compareTo(a.getCreatedDate()))
                .collect(Collectors.toList());

        int offset = 0;
        if (lastEvaluatedKey != null && lastEvaluatedKey.containsKey("offset")) {
            offset = Integer.parseInt(lastEvaluatedKey.get("offset"));
        }

        List<UrlMappingItem> page = all.stream()
                .skip(offset)
                .limit(pageSize)
                .collect(Collectors.toList());

        Map<String, String> nextKey = null;
        if (offset + pageSize < all.size()) {
            nextKey = Map.of("offset", String.valueOf(offset + pageSize));
        }

        return new DynamoPageResult<>(page, nextKey);
    }

    @Override
    public Optional<UrlMappingItem> findById(Long id) {
        return store.values().stream()
                .filter(i -> i.getId().equals(id))
                .findFirst();
    }

    @Override
    public Optional<UrlMappingItem> findByIdAndOwnerId(Long id, Long ownerId) {
        return findById(id)
                .filter(i -> i.getOwnerId().equals(ownerId));
    }

    @Override
    public long countByOwnerId(Long ownerId) {
        return store.values().stream()
                .filter(i -> i.getOwnerId().equals(ownerId))
                .count();
    }

    @Override
    public void deleteByShortUrl(String shortUrl) {
        store.remove(shortUrl);
    }

    @Override
    public void incrementClickCount(String shortUrl) {
        store.computeIfPresent(shortUrl, (k, v) -> {
            v.setClickCount((v.getClickCount() != null ? v.getClickCount() : 0) + 1);
            return v;
        });
    }

    @Override
    public boolean updateOriginalUrl(String shortUrl, String originalUrl, String updatedDate) {
        return store.computeIfPresent(shortUrl, (k, v) -> {
            v.setOriginalUrl(originalUrl);
            v.setUpdatedDate(updatedDate);
            return v;
        }) != null;
    }
}
