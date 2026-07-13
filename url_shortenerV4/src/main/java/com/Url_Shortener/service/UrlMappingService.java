package com.Url_Shortener.service;

import com.Url_Shortener.cache.CacheService;
import com.Url_Shortener.cache.CachedUrlData;
import com.Url_Shortener.dto.ClickEventDetailDto;
import com.Url_Shortener.dto.ClickEventDto;
import com.Url_Shortener.dto.DashboardDto;
import com.Url_Shortener.dto.UrlMappingDto;
import com.Url_Shortener.entity.ClickEvent;
import com.Url_Shortener.entity.UrlMapping;
import com.Url_Shortener.entity.User;
import com.Url_Shortener.model.UrlMappingItem;
import com.Url_Shortener.repository.ClickEventRepository;
import com.Url_Shortener.repository.UrlMappingDynamoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * ============================================================
 * POLYGLOT PERSISTENCE MIGRATION: URL Mapping Service
 * ============================================================
 *
 * URL mapping persistence has been migrated from PostgreSQL (JPA)
 * to DynamoDB Local. Click events remain in PostgreSQL for now.
 *
 * Changes:
 *   - UrlMappingRepository (JPA) → UrlMappingDynamoRepository (DynamoDB)
 *   - ClickEventRepository methods now use String shortUrl instead of
 *     UrlMapping entity references (cross-database FK decoupling)
 *   - clickCount native SQL → DynamoDB atomic UpdateExpression
 *   - Pageable support adapted for DynamoDB token-based pagination
 *   - Redis cache-aside layer added for redirect hot-path optimization
 * ============================================================
 */
@Service
public class UrlMappingService {

    // OLD POSTGRES IMPLEMENTATION
    // private final UrlMappingRepository urlMappingRepository;

    // NEW DYNAMODB IMPLEMENTATION
    private final UrlMappingDynamoRepository urlMappingRepository;
    private final ClickEventRepository clickEventRepository;
    private final CacheService cacheService;

    public UrlMappingService(UrlMappingDynamoRepository urlMappingRepository,
                             ClickEventRepository clickEventRepository,
                             CacheService cacheService) {
        this.urlMappingRepository = urlMappingRepository;
        this.clickEventRepository = clickEventRepository;
        this.cacheService = cacheService;
    }

    /**
     * Creates a new short URL for the given original URL and user.
     */
    public UrlMappingDto createShortUrl(String originalUrl, User user) {
        // OLD POSTGRES: urlMappingRepository.existsByOriginalUrlAndUser(originalUrl, user)
        // NEW DYNAMODB:  Check by querying ownerId-index GSI and filtering by originalUrl
        if (urlMappingRepository.existsByOriginalUrlAndOwnerId(originalUrl, user.getId())) {
            List<UrlMappingItem> userUrls = urlMappingRepository.findByOwnerId(user.getId());
            UrlMappingItem existing = userUrls.stream()
                    .filter(m -> m.getOriginalUrl().equals(originalUrl))
                    .findFirst()
                    .orElseThrow();
            return convertToDto(existing);
        }

        String shortUrl = generateShortUrl();
        Long generatedId = generateId();

        UrlMappingItem item = new UrlMappingItem();
        item.setShortUrl(shortUrl);
        item.setId(generatedId);
        item.setOriginalUrl(originalUrl);
        item.setOwnerId(user.getId());
        item.setOwnerUsername(user.getUsername());
        item.setClickCount(0);
        String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        item.setCreatedDate(now);
        item.setUpdatedDate(now);

        UrlMappingItem saved = urlMappingRepository.save(item);

        // POLYGLOT PERSISTENCE: Populate Redis cache after DynamoDB write.
        // If Redis is unavailable, this is a no-op — the cache will be
        // populated on the next redirect (cache-aside pattern).
        cacheService.put(shortUrl, CachedUrlData.from(saved));

        return convertToDto(saved);
    }

    /**
     * Generates a unique numeric ID for the URL mapping.
     * Uses a random positive Long — collisions are astronomically unlikely
     * but the uniqueness is also protected by the DynamoDB id-index GSI.
     */
    private Long generateId() {
        return Math.abs(new SecureRandom().nextLong());
    }

    /**
     * Converts a DynamoDB UrlMappingItem to a UrlMappingDto.
     */
    // OLD POSTGRES: convertToDto(UrlMapping urlMapping) — used JPA entity
    // NEW DYNAMODB:  convertToDto(UrlMappingItem item) — uses DynamoDB model
    private UrlMappingDto convertToDto(UrlMappingItem item) {
        UrlMappingDto dto = new UrlMappingDto();
        dto.setId(item.getId());
        dto.setOriginalUrl(item.getOriginalUrl());
        dto.setShortUrl(item.getShortUrl());
        dto.setClickCount(item.getClickCount() != null ? item.getClickCount() : 0);
        dto.setCreatedDate(item.getCreatedDate() != null
                ? LocalDateTime.parse(item.getCreatedDate(), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                : null);
        dto.setUsername(item.getOwnerUsername());
        return dto;
    }

    /**
     * Generates an 8-character random short URL.
     * Checks DynamoDB for collision (GetItem on PK).
     */
    private String generateShortUrl() {
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        String shortUrl;

        do {
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                sb.append(characters.charAt(random.nextInt(characters.length())));
            }
            shortUrl = sb.toString();
        } while (urlMappingRepository.existsByShortUrl(shortUrl));

        return shortUrl;
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
     * Returns paginated URL mappings for a user.
     *
     * NOTE: DynamoDB uses token-based pagination (LastEvaluatedKey), not
     * offset-based pagination like SQL. The Spring Pageable 'page' / 'offset'
     * semantics are approximated — page 0 always works, but subsequent pages
     * require passing the LastEvaluatedKey from the previous response.
     * For simplicity, this implementation treats each call independently
     * and only supports "first page" semantics for the page number.
     */
    public Page<UrlMappingDto> getUrlsByUser(User user, Pageable pageable) {
        int pageSize = pageable.getPageSize();
        boolean ascending = pageable.getSort().stream()
                .findFirst()
                .map(order -> order.getDirection() == Sort.Direction.ASC)
                .orElse(false);

        // Token-based pagination: page 0 = first page; for subsequent pages
        // we'd need to pass the LastEvaluatedKey from the client.
        // Since Spring Pageable is offset-based, we only support first-page
        // semantics here. Full token-based pagination would require API changes.
        UrlMappingDynamoRepository.DynamoPageResult<UrlMappingItem> result =
                urlMappingRepository.findByOwnerIdPaginated(user.getId(), pageSize, null, ascending);

        List<UrlMappingDto> dtos = result.getItems().stream()
                .map(this::convertToDto)
                .toList();

        long total = urlMappingRepository.countByOwnerId(user.getId());

        return new PageImpl<>(dtos,
                PageRequest.of(pageable.getPageNumber(), pageSize, pageable.getSort()),
                total);
    }

    /**
     * Returns click event counts grouped by date for a specific URL.
     */
    public List<ClickEventDto> getClickEventsByDate(String shortUrl, LocalDateTime start,
                                                     LocalDateTime end, User user) {
        // OLD POSTGRES: urlMappingRepository.findByShortUrlAndUser(shortUrl, user)
        // NEW DYNAMODB:  findByShortUrlAndOwnerId
        Optional<UrlMappingItem> optItem =
                urlMappingRepository.findByShortUrlAndOwnerId(shortUrl, user.getId());

        if (optItem.isPresent()) {
            // OLD POSTGRES: clickEventRepository.findByUrlMappingAndClickDateBetween(urlMapping, start, end)
            // NEW DYNAMODB:  findByShortUrlAndClickDateBetween(shortUrl, start, end)
            return clickEventRepository.findByShortUrlAndClickDateBetween(shortUrl, start, end).stream()
                    .collect(Collectors.groupingBy(click -> click.getClickDate().toLocalDate(),
                            Collectors.counting()))
                    .entrySet().stream()
                    .map(entry -> {
                        ClickEventDto dto = new ClickEventDto();
                        dto.setClickDate(entry.getKey());
                        dto.setCount(entry.getValue());
                        return dto;
                    })
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    /**
     * Returns total click counts for all URLs belonging to a user, grouped by date.
     */
    public List<ClickEventDto> getTotalClicksByUserAndDate(User user, LocalDate start, LocalDate end) {
        // OLD POSTGRES: urlMappingRepository.findByUser(user) — list of JPA entities
        // NEW DYNAMODB:  findByOwnerId(user.getId()) — list of DynamoDB items
        List<UrlMappingItem> userUrls = urlMappingRepository.findByOwnerId(user.getId());
        List<String> shortUrls = userUrls.stream()
                .map(UrlMappingItem::getShortUrl)
                .collect(Collectors.toList());

        if (shortUrls.isEmpty()) {
            return Collections.emptyList();
        }

        // OLD POSTGRES: clickEventRepository.findByUrlMappingInAndClickDateBetween(urlMappings, ...)
        // NEW DYNAMODB:  findByShortUrlInAndClickDateBetween(shortUrls, ...)
        List<ClickEvent> clickEvents = clickEventRepository.findByShortUrlInAndClickDateBetween(
                shortUrls, start.atStartOfDay(), end.plusDays(1).atStartOfDay());

        return clickEvents.stream()
                .collect(Collectors.groupingBy(click -> click.getClickDate().toLocalDate(),
                        Collectors.counting()))
                .entrySet().stream()
                .map(entry -> {
                    ClickEventDto dto = new ClickEventDto();
                    dto.setClickDate(entry.getKey());
                    dto.setCount(entry.getValue());
                    return dto;
                })
                .sorted((a, b) -> a.getClickDate().compareTo(b.getClickDate()))
                .collect(Collectors.toList());
    }

    /**
     * Resolves a short URL to its original URL and logs the click event.
     * This is the HOT PATH — called on every redirect.
     *
     * CACHE-ASIDE PATTERN:
     *   1. Look up Redis first.
     *   2. On HIT → increment clickCount in DynamoDB, log click event, return.
     *   3. On MISS → read from DynamoDB, populate Redis, then proceed.
     *   4. If Redis is unavailable → fall back to DynamoDB directly.
     *
     * NOTE: Returns UrlMapping (plain POJO, not JPA entity) for controller
     * compatibility. The UrlMapping entity has been de-JPA-annotated.
     */
    public UrlMapping getOriginalUrl(String shortUrl, String ipAddress, String referrer, String userAgent) {
        // STEP 1: Try Redis cache first (silently falls back to DynamoDB on error)
        Optional<CachedUrlData> cached = cacheService.get(shortUrl);

        if (cached.isPresent()) {
            // CACHE HIT — we know the URL exists. Increment clickCount in DynamoDB
            // and log the click event. No need to re-read DynamoDB for the mapping.
            CachedUrlData data = cached.get();
            urlMappingRepository.incrementClickCount(shortUrl);
            logClickEvent(shortUrl, ipAddress, referrer, userAgent);
            return buildUrlMappingFromCache(data);
        }

        // STEP 2: CACHE MISS or Redis unavailable — read from DynamoDB
        Optional<UrlMappingItem> optItem = urlMappingRepository.findByShortUrl(shortUrl);

        if (optItem.isPresent()) {
            UrlMappingItem item = optItem.get();

            // Populate Redis for next lookup (best-effort, silently fails)
            cacheService.put(shortUrl, CachedUrlData.from(item));

            // Increment clickCount and log the click event
            urlMappingRepository.incrementClickCount(shortUrl);
            logClickEvent(shortUrl, ipAddress, referrer, userAgent);

            return buildUrlMappingFromItem(item);
        }

        return null;
    }

    /**
     * Logs a click event to PostgreSQL (cross-database operation).
     */
    private void logClickEvent(String shortUrl, String ipAddress, String referrer, String userAgent) {
        ClickEvent clickEvent = new ClickEvent();
        clickEvent.setClickDate(LocalDateTime.now());
        clickEvent.setShortUrl(shortUrl);
        clickEvent.setIpAddress(ipAddress);
        clickEvent.setReferrer(referrer);
        clickEvent.setUserAgent(userAgent);
        clickEventRepository.save(clickEvent);
    }

    /**
     * Builds a UrlMapping POJO from a DynamoDB UrlMappingItem.
     */
    private UrlMapping buildUrlMappingFromItem(UrlMappingItem item) {
        UrlMapping mapping = new UrlMapping();
        mapping.setId(item.getId());
        mapping.setShortUrl(item.getShortUrl());
        mapping.setOriginalUrl(item.getOriginalUrl());
        mapping.setClickCount(item.getClickCount() != null ? item.getClickCount() : 0);
        if (item.getCreatedDate() != null) {
            mapping.setCreatedDate(LocalDateTime.parse(item.getCreatedDate(),
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }
        return mapping;
    }

    /**
     * Builds a UrlMapping POJO from a Redis CachedUrlData (cache hit path).
     * Avoids an unnecessary DynamoDB GetItem when the data is already in cache.
     */
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
        // OLD POSTGRES: urlMappingRepository.findByIdAndUser(id, user)
        // NEW DYNAMODB:  findByIdAndOwnerId(id, user.getId())
        UrlMappingItem item = urlMappingRepository.findByIdAndOwnerId(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("URL mapping not found or access denied"));
        return convertToDto(item);
    }

    /**
     * Updates the original URL for a given mapping.
     */
    public UrlMappingDto updateUrl(Long id, String newOriginalUrl, User user) {
        // OLD POSTGRES: urlMappingRepository.findByIdAndUser(id, user)
        // NEW DYNAMODB:  findByIdAndOwnerId(id, user.getId())
        UrlMappingItem item = urlMappingRepository.findByIdAndOwnerId(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("URL mapping not found or access denied"));

        if (newOriginalUrl != null && !newOriginalUrl.isBlank()) {
            item.setOriginalUrl(newOriginalUrl);
            item.setUpdatedDate(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }

        // OLD POSTGRES: urlMappingRepository.save(urlMapping) — JPA merge
        // NEW DYNAMODB:  save(item) — PutItem (upsert)
        UrlMappingItem saved = urlMappingRepository.save(item);

        // POLYGLOT PERSISTENCE: Update Redis cache with the new value.
        // Best-effort — silently fails if Redis is unavailable.
        cacheService.put(saved.getShortUrl(), CachedUrlData.from(saved));

        return convertToDto(saved);
    }

    /**
     * Deletes a URL mapping and its associated click events.
     *
     * NOTE: This is a cross-database operation. The URL mapping is deleted
     * from DynamoDB and click events from PostgreSQL. There is no distributed
     * transaction — if one delete fails, data inconsistency may occur.
     * (This will be addressed when analytics migrate to MongoDB.)
     */
    public void deleteUrl(Long id, User user) {
        // OLD POSTGRES: urlMappingRepository.findByIdAndUser(id, user)
        // NEW DYNAMODB:  findByIdAndOwnerId(id, user.getId())
        UrlMappingItem item = urlMappingRepository.findByIdAndOwnerId(id, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("URL mapping not found or access denied"));

        // OLD POSTGRES: clickEventRepository.deleteByUrlMapping(urlMapping)
        // NEW DYNAMODB:  clickEventRepository.deleteByShortUrl(shortUrl)
        clickEventRepository.deleteByShortUrl(item.getShortUrl());

        // OLD POSTGRES: urlMappingRepository.delete(urlMapping)
        // NEW DYNAMODB:  deleteByShortUrl(shortUrl)
        urlMappingRepository.deleteByShortUrl(item.getShortUrl());

        // POLYGLOT PERSISTENCE: Evict from Redis cache after DynamoDB delete.
        // Best-effort — if Redis is unavailable, the TTL will expire the
        // stale entry eventually.
        cacheService.evict(item.getShortUrl());
    }

    /**
     * Returns detailed click events for a specific URL within a date range.
     */
    public List<ClickEventDetailDto> getClickEventDetails(String shortUrl, LocalDateTime start,
                                                           LocalDateTime end, User user) {
        // OLD POSTGRES: urlMappingRepository.findByShortUrlAndUser(shortUrl, user)
        // NEW DYNAMODB:  findByShortUrlAndOwnerId
        Optional<UrlMappingItem> optItem =
                urlMappingRepository.findByShortUrlAndOwnerId(shortUrl, user.getId());

        if (optItem.isEmpty()) {
            throw new IllegalArgumentException("Short URL not found or access denied");
        }

        return clickEventRepository.findByShortUrlAndClickDateBetween(shortUrl, start, end).stream()
                .map(click -> new ClickEventDetailDto(
                        click.getId(),
                        click.getClickDate(),
                        click.getIpAddress(),
                        click.getReferrer(),
                        click.getUserAgent()
                ))
                .toList();
    }

    /**
     * Builds a dashboard summary for the user.
     * Aggregates data from DynamoDB (URL metadata) and PostgreSQL (click events).
     */
    public DashboardDto getDashboard(User user) {
        // OLD POSTGRES: urlMappingRepository.findByUser(user) — JPA
        // NEW DYNAMODB:  findByOwnerId(user.getId()) — DynamoDB
        List<UrlMappingItem> userUrls = urlMappingRepository.findByOwnerId(user.getId());

        long totalUrls = userUrls.size();
        long totalClicks = userUrls.stream()
                .mapToInt(item -> item.getClickCount() != null ? item.getClickCount() : 0)
                .sum();

        List<String> shortUrls = userUrls.stream()
                .map(UrlMappingItem::getShortUrl)
                .collect(Collectors.toList());

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime todayEnd = todayStart.plusDays(1);

        long clicksToday = shortUrls.isEmpty() ? 0 :
                clickEventRepository.countByShortUrlInAndClickDateBetween(shortUrls, todayStart, todayEnd);

        LocalDateTime weekStart = LocalDate.now().minusDays(7).atStartOfDay();
        long clicksThisWeek = shortUrls.isEmpty() ? 0 :
                clickEventRepository.countByShortUrlInAndClickDateBetween(shortUrls, weekStart, todayEnd);

        List<UrlMappingDto> topUrls = userUrls.stream()
                .sorted((a, b) -> Integer.compare(
                        b.getClickCount() != null ? b.getClickCount() : 0,
                        a.getClickCount() != null ? a.getClickCount() : 0))
                .limit(5)
                .map(this::convertToDto)
                .toList();

        return new DashboardDto(totalUrls, totalClicks, clicksToday, clicksThisWeek, topUrls);
    }

    /**
     * Returns the top N URLs by total click count.
     */
    public List<UrlMappingDto> getTopUrls(User user, int limit) {
        return urlMappingRepository.findByOwnerId(user.getId()).stream()
                .sorted((a, b) -> Integer.compare(
                        b.getClickCount() != null ? b.getClickCount() : 0,
                        a.getClickCount() != null ? a.getClickCount() : 0))
                .limit(limit)
                .map(this::convertToDto)
                .toList();
    }

    /**
     * Returns the top N URLs by click count within a specific date range.
     * NOTE: This is a cross-database N+1 operation — for each URL, we query
     * PostgreSQL for click counts. This will be optimized when analytics
     * migrate to MongoDB in a future phase.
     */
    public List<UrlMappingDto> getTopUrlsByDate(User user, int limit, LocalDate start, LocalDate end) {
        List<UrlMappingItem> userUrls = urlMappingRepository.findByOwnerId(user.getId());

        return userUrls.stream()
                .sorted((a, b) -> {
                    long clicksA = clickEventRepository.countByShortUrlAndClickDateBetween(
                            a.getShortUrl(), start.atStartOfDay(), end.plusDays(1).atStartOfDay());
                    long clicksB = clickEventRepository.countByShortUrlAndClickDateBetween(
                            b.getShortUrl(), start.atStartOfDay(), end.plusDays(1).atStartOfDay());
                    return Long.compare(clicksB, clicksA);
                })
                .limit(limit)
                .map(this::convertToDto)
                .toList();
    }
}
