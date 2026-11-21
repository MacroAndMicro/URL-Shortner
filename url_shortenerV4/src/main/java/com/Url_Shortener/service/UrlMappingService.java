package com.Url_Shortener.service;

import com.Url_Shortener.cache.CacheService;
import com.Url_Shortener.cache.CachedUrlData;
import com.Url_Shortener.dto.ClickEventDetailDto;
import com.Url_Shortener.dto.ClickEventDto;
import com.Url_Shortener.dto.CursorPageDto;
import com.Url_Shortener.dto.DashboardDto;
import com.Url_Shortener.dto.UrlMappingDto;
import com.Url_Shortener.entity.UrlMapping;
import com.Url_Shortener.entity.User;
import com.Url_Shortener.event.ClickEventCreatedEvent;
import com.Url_Shortener.exception.UrlMappingNotFoundException;
import com.Url_Shortener.metrics.UrlShortenerMetrics;
import com.Url_Shortener.model.UrlMappingItem;
import com.Url_Shortener.publisher.AnalyticsPublisher;
import com.Url_Shortener.repository.UrlMappingDynamoRepository;
import com.Url_Shortener.security.IpHasher;
import com.Url_Shortener.storage.AnalyticsStorage;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * ============================================================
 * URL Mapping Service
 * ============================================================
 *
 * URL mappings are stored in DynamoDB and cached in Redis.
 * Click events are published to Kafka; the consumer writes them
 * to MongoDB and updates clickCount in DynamoDB, so clickCount
 * and analytics are eventually consistent with redirects.
 */
@Service
public class UrlMappingService {

    private static final String SHORT_URL_CHARACTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int SHORT_URL_LENGTH = 8;
    private static final int MAX_SHORT_URL_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UrlMappingDynamoRepository urlMappingRepository;
    private final AnalyticsStorage analyticsStorage;
    private final AnalyticsPublisher analyticsPublisher;
    private final CacheService cacheService;
    private final UrlShortenerMetrics metrics;
    private final IpHasher ipHasher;

    public UrlMappingService(UrlMappingDynamoRepository urlMappingRepository,
                             AnalyticsStorage analyticsStorage,
                             AnalyticsPublisher analyticsPublisher,
                             CacheService cacheService,
                             UrlShortenerMetrics metrics,
                             IpHasher ipHasher) {
        this.urlMappingRepository = urlMappingRepository;
        this.analyticsStorage = analyticsStorage;
        this.analyticsPublisher = analyticsPublisher;
        this.cacheService = cacheService;
        this.metrics = metrics;
        this.ipHasher = ipHasher;
    }

    /**
     * Creates a new short URL for the given original URL and user.
     * Returns the user's existing mapping if they already shortened this URL.
     */
    public UrlMappingDto createShortUrl(String originalUrl, User user) {
        var sample = metrics.startTimer();
        metrics.incrementDynamoRead();
        Optional<UrlMappingItem> existing = urlMappingRepository.findByOwnerId(user.getId()).stream()
                .filter(m -> m.getOriginalUrl().equals(originalUrl))
                .findFirst();
        if (existing.isPresent()) {
            metrics.stopUrlCreationTime(sample);
            return convertToDto(existing.get());
        }

        UrlMappingItem item = new UrlMappingItem();
        item.setId(generateId());
        item.setOriginalUrl(originalUrl);
        item.setOwnerId(user.getId());
        item.setOwnerUsername(user.getUsername());
        item.setClickCount(0);
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        item.setCreatedDate(now);
        item.setUpdatedDate(now);

        metrics.incrementDynamoWrite();
        saveWithUniqueShortUrl(item);

        // Best-effort: if Redis is unavailable, the cache is filled on the next redirect.
        cacheService.put(item.getShortUrl(), CachedUrlData.from(item));

        metrics.stopUrlCreationTime(sample);
        metrics.incrementUrlCreation();
        return convertToDto(item);
    }

    private void saveWithUniqueShortUrl(UrlMappingItem item) {
        for (int attempt = 0; attempt < MAX_SHORT_URL_ATTEMPTS; attempt++) {
            item.setShortUrl(generateShortUrl());
            if (urlMappingRepository.saveIfNotExists(item)) {
                return;
            }
        }
        throw new IllegalStateException("Could not allocate a unique short URL");
    }

    /**
     * Generates a random positive ID for the REST API {id} endpoints.
     * Collisions are extremely unlikely but are not prevented: the id-index
     * GSI does not enforce uniqueness.
     */
    private Long generateId() {
        return RANDOM.nextLong() & Long.MAX_VALUE;
    }

    private UrlMappingDto convertToDto(UrlMappingItem item) {
        UrlMappingDto dto = new UrlMappingDto();
        dto.setId(item.getId());
        dto.setOriginalUrl(item.getOriginalUrl());
        dto.setShortUrl(item.getShortUrl());
        dto.setClickCount(clickCountOf(item));
        dto.setCreatedDate(item.getCreatedDate() != null
                ? LocalDateTime.parse(item.getCreatedDate(), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                : null);
        dto.setUsername(item.getOwnerUsername());
        return dto;
    }

    private int clickCountOf(UrlMappingItem item) {
        return item.getClickCount() != null ? item.getClickCount() : 0;
    }

    private String generateShortUrl() {
        StringBuilder sb = new StringBuilder(SHORT_URL_LENGTH);
        for (int i = 0; i < SHORT_URL_LENGTH; i++) {
            sb.append(SHORT_URL_CHARACTERS.charAt(RANDOM.nextInt(SHORT_URL_CHARACTERS.length())));
        }
        return sb.toString();
    }

    /**
     * Returns all URL mappings for a user.
     */
    public List<UrlMappingDto> getUrlsByUser(User user) {
        return urlMappingRepository.findByOwnerId(user.getId()).stream()
                .map(this::convertToDto)
                .toList();
    }

    /**
     * Returns one page of a user's URL mappings, ordered by creation date.
     * The cursor comes from the previous page's nextCursor (null for the first
     * page) and is only valid for the sort direction it was issued with.
     */
    public CursorPageDto<UrlMappingDto> getUrlsByUser(User user, int size, String cursor, boolean ascending) {
        UrlMappingDynamoRepository.DynamoPageResult<UrlMappingItem> result =
                urlMappingRepository.findByOwnerIdPaginated(
                        user.getId(), size, PageCursor.decode(cursor), ascending);

        List<UrlMappingDto> dtos = result.getItems().stream()
                .map(this::convertToDto)
                .toList();

        long total = urlMappingRepository.countByOwnerId(user.getId());

        return new CursorPageDto<>(dtos, PageCursor.encode(result.getLastEvaluatedKey()), size, total);
    }

    /**
     * Returns click event counts grouped by date for a specific URL.
     */
    public List<ClickEventDto> getClickEventsByDate(String shortUrl, LocalDateTime start,
                                                     LocalDateTime end, User user) {
        Optional<UrlMappingItem> optItem =
                urlMappingRepository.findByShortUrlAndOwnerId(shortUrl, user.getId());

        if (optItem.isPresent()) {
            metrics.incrementAnalyticsRequest();
            return analyticsStorage.findAggregatedByShortUrlAndDateBetween(shortUrl, start, end);
        }
        return Collections.emptyList();
    }

    /**
     * Returns total click counts for all URLs belonging to a user, grouped by date.
     */
    public List<ClickEventDto> getTotalClicksByUserAndDate(User user, LocalDate start, LocalDate end) {
        metrics.incrementAnalyticsRequest();
        List<String> shortUrls = shortUrlsOf(urlMappingRepository.findByOwnerId(user.getId()));

        return analyticsStorage.findAggregatedByShortUrlsAndDateBetween(
                shortUrls, start.atStartOfDay(), end.plusDays(1).atStartOfDay());
    }

    private List<String> shortUrlsOf(List<UrlMappingItem> items) {
        return items.stream()
                .map(UrlMappingItem::getShortUrl)
                .collect(Collectors.toList());
    }

    /**
     * Resolves a short URL to its original URL and publishes a click event.
     * This is the HOT PATH — called on every redirect.
     *
     * CACHE-ASIDE PATTERN:
     *   1. Look up Redis first. A hit needs no DynamoDB call.
     *   2. Skip DynamoDB if this code was recently found not to exist.
     *   3. Otherwise read from DynamoDB and populate Redis.
     *   4. If Redis is unavailable, every lookup falls back to DynamoDB.
     *
     * clickCount is updated by the Kafka consumer, not here.
     */
    public UrlMapping getOriginalUrl(String shortUrl, String ipAddress, String referrer, String userAgent) {
        var totalSample = metrics.startTimer();
        metrics.incrementRedirectRequest();

        var redisSample = metrics.startTimer();
        Optional<CachedUrlData> cached = cacheService.get(shortUrl);
        metrics.stopRedisLookupTime(redisSample);

        if (cached.isPresent()) {
            metrics.incrementCacheHit();
            logClickEvent(shortUrl, ipAddress, referrer, userAgent);
            metrics.stopRedirectTotalTime(totalSample);
            metrics.incrementRedirectSuccess();
            return buildUrlMappingFromCache(cached.get());
        }

        metrics.incrementCacheMiss();

        if (cacheService.isMarkedMissing(shortUrl)) {
            metrics.stopRedirectTotalTime(totalSample);
            metrics.incrementRedirectFailure();
            return null;
        }

        metrics.incrementDynamoRead();
        var dynamoSample = metrics.startTimer();
        Optional<UrlMappingItem> optItem = urlMappingRepository.findByShortUrl(shortUrl);
        metrics.stopDynamoLookupTime(dynamoSample);

        if (optItem.isPresent()) {
            UrlMappingItem item = optItem.get();
            cacheService.put(shortUrl, CachedUrlData.from(item));
            logClickEvent(shortUrl, ipAddress, referrer, userAgent);
            metrics.stopRedirectTotalTime(totalSample);
            metrics.incrementRedirectSuccess();
            return buildUrlMappingFromItem(item);
        }

        cacheService.markMissing(shortUrl);
        metrics.stopRedirectTotalTime(totalSample);
        metrics.incrementRedirectFailure();
        return null;
    }

    /**
     * Publishes a click event to Kafka. The IP is hashed first so that
     * raw addresses are not written to Kafka or MongoDB.
     */
    private void logClickEvent(String shortUrl, String ipAddress, String referrer, String userAgent) {
        ClickEventCreatedEvent event = new ClickEventCreatedEvent(
                shortUrl,
                null,  // originalUrl is not needed by the consumer
                ipHasher.hash(ipAddress),
                referrer,
                userAgent,
                null   // ownerId is not needed by the consumer
        );
        analyticsPublisher.publish(event);
    }

    private UrlMapping buildUrlMappingFromItem(UrlMappingItem item) {
        UrlMapping mapping = new UrlMapping();
        mapping.setId(item.getId());
        mapping.setShortUrl(item.getShortUrl());
        mapping.setOriginalUrl(item.getOriginalUrl());
        mapping.setClickCount(clickCountOf(item));
        if (item.getCreatedDate() != null) {
            mapping.setCreatedDate(LocalDateTime.parse(item.getCreatedDate(),
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }
        return mapping;
    }

    private UrlMapping buildUrlMappingFromCache(CachedUrlData data) {
        UrlMapping mapping = new UrlMapping();
        mapping.setShortUrl(data.getShortUrl());
        mapping.setOriginalUrl(data.getOriginalUrl());
        mapping.setClickCount(data.getClickCount() != null ? data.getClickCount() : 0);
        if (data.getCreatedDate() != null) {
            mapping.setCreatedDate(LocalDateTime.parse(data.getCreatedDate(),
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }
        return mapping;
    }

    /**
     * Finds a URL mapping by its numeric ID, verifying user ownership.
     */
    public UrlMappingDto getUrlByIdAndUser(Long id, User user) {
        return convertToDto(findOwnedItem(id, user));
    }

    private UrlMappingItem findOwnedItem(Long id, User user) {
        return urlMappingRepository.findByIdAndOwnerId(id, user.getId())
                .orElseThrow(() -> new UrlMappingNotFoundException("URL mapping not found or access denied"));
    }

    /**
     * Updates the original URL for a given mapping. Only the changed attributes
     * are written, so concurrent click count increments are not lost.
     */
    public UrlMappingDto updateUrl(Long id, String newOriginalUrl, User user) {
        UrlMappingItem item = findOwnedItem(id, user);

        if (newOriginalUrl != null && !newOriginalUrl.isBlank()) {
            String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            if (!urlMappingRepository.updateOriginalUrl(item.getShortUrl(), newOriginalUrl, now)) {
                throw new UrlMappingNotFoundException("URL mapping not found or access denied");
            }
            item.setOriginalUrl(newOriginalUrl);
            item.setUpdatedDate(now);

            // Best-effort: on failure the old target is served until the TTL expires.
            cacheService.put(item.getShortUrl(), CachedUrlData.from(item));
        }

        return convertToDto(item);
    }

    /**
     * Deletes a URL mapping and its associated click events.
     *
     * NOTE: This is a cross-database operation with no distributed transaction.
     * The mapping is deleted first so that a MongoDB failure leaves orphaned
     * analytics rather than a live link with no history.
     */
    public void deleteUrl(Long id, User user) {
        UrlMappingItem item = findOwnedItem(id, user);

        urlMappingRepository.deleteByShortUrl(item.getShortUrl());

        // Best-effort: on failure the stale entry expires via its TTL.
        cacheService.evict(item.getShortUrl());
        analyticsStorage.deleteByShortUrl(item.getShortUrl());
        metrics.incrementUrlDeletion();
    }

    /**
     * Returns detailed click events for a specific URL within a date range.
     */
    public List<ClickEventDetailDto> getClickEventDetails(String shortUrl, LocalDateTime start,
                                                           LocalDateTime end, User user) {
        Optional<UrlMappingItem> optItem =
                urlMappingRepository.findByShortUrlAndOwnerId(shortUrl, user.getId());

        if (optItem.isEmpty()) {
            throw new UrlMappingNotFoundException("Short URL not found or access denied");
        }

        metrics.incrementAnalyticsRequest();
        return analyticsStorage.findByShortUrlAndDateBetween(shortUrl, start, end);
    }

    /**
     * Builds a dashboard summary for the user.
     * Aggregates data from DynamoDB (URL metadata) and MongoDB (click events).
     */
    public DashboardDto getDashboard(User user) {
        metrics.incrementAnalyticsRequest();
        List<UrlMappingItem> userUrls = urlMappingRepository.findByOwnerId(user.getId());

        long totalUrls = userUrls.size();
        long totalClicks = userUrls.stream().mapToLong(this::clickCountOf).sum();

        List<String> shortUrls = shortUrlsOf(userUrls);

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime todayEnd = todayStart.plusDays(1);
        long clicksToday = analyticsStorage.countByShortUrlsAndDateBetween(shortUrls, todayStart, todayEnd);

        LocalDateTime weekStart = LocalDate.now().minusDays(7).atStartOfDay();
        long clicksThisWeek = analyticsStorage.countByShortUrlsAndDateBetween(shortUrls, weekStart, todayEnd);

        List<UrlMappingDto> topUrls = topByTotalClicks(userUrls, 5);

        return new DashboardDto(totalUrls, totalClicks, clicksToday, clicksThisWeek, topUrls);
    }

    /**
     * Returns the top N URLs by total click count.
     */
    public List<UrlMappingDto> getTopUrls(User user, int limit) {
        return topByTotalClicks(urlMappingRepository.findByOwnerId(user.getId()), limit);
    }

    private List<UrlMappingDto> topByTotalClicks(List<UrlMappingItem> items, int limit) {
        return items.stream()
                .sorted(Comparator.comparingInt(this::clickCountOf).reversed())
                .limit(limit)
                .map(this::convertToDto)
                .toList();
    }

    /**
     * Returns the top N URLs by click count within a date range.
     * Each returned clickCount is the count for that range, not the all-time total.
     */
    public List<UrlMappingDto> getTopUrlsByDate(User user, int limit, LocalDate start, LocalDate end) {
        metrics.incrementAnalyticsRequest();
        List<UrlMappingItem> userUrls = urlMappingRepository.findByOwnerId(user.getId());
        Map<String, Long> counts = analyticsStorage.countPerShortUrlAndDateBetween(
                shortUrlsOf(userUrls), start.atStartOfDay(), end.plusDays(1).atStartOfDay());

        return userUrls.stream()
                .sorted(Comparator.comparingLong(
                        (UrlMappingItem item) -> counts.getOrDefault(item.getShortUrl(), 0L)).reversed())
                .limit(limit)
                .map(item -> {
                    UrlMappingDto dto = convertToDto(item);
                    dto.setClickCount(counts.getOrDefault(item.getShortUrl(), 0L).intValue());
                    return dto;
                })
                .toList();
    }
}
