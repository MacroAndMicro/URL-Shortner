package com.Url_Shortener.service;

import com.Url_Shortener.cache.CacheService;
import com.Url_Shortener.cache.CachedUrlData;
import com.Url_Shortener.dto.ClickEventDetailDto;
import com.Url_Shortener.dto.ClickEventDto;
import com.Url_Shortener.dto.CursorPageDto;
import com.Url_Shortener.dto.DashboardDto;
import com.Url_Shortener.dto.UrlMappingDto;
import com.Url_Shortener.entity.User;
import com.Url_Shortener.event.ClickEventCreatedEvent;
import com.Url_Shortener.exception.LimitExceededException;
import com.Url_Shortener.exception.UrlMappingNotFoundException;
import com.Url_Shortener.metrics.UrlShortenerMetrics;
import com.Url_Shortener.model.UrlMappingItem;
import com.Url_Shortener.plan.GuestQuotaService;
import com.Url_Shortener.plan.Plan;
import com.Url_Shortener.plan.PlanService;
import com.Url_Shortener.publisher.AnalyticsPublisher;
import com.Url_Shortener.repository.UrlMappingDynamoRepository;
import com.Url_Shortener.security.IpHasher;
import com.Url_Shortener.shortcode.Base62;
import com.Url_Shortener.shortcode.SnowflakeIdGenerator;
import com.Url_Shortener.storage.AnalyticsStorage;

import org.springframework.stereotype.Service;

import java.time.Duration;
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
 *
 * Plan rules (see Plan) are applied here: link limits, link expiry,
 * 301 vs 302 redirects, and which users get analytics. Expiry is
 * evaluated against the owner's current plan whenever a link is used,
 * so expired links stop resolving and drop out of history without a
 * cleanup job.
 */
@Service
public class UrlMappingService {

    /**
     * Snowflake IDs never repeat, so a taken code only happens if a custom
     * code already claimed it. A fresh ID then gives a different code.
     */
    private static final int MAX_SHORT_URL_ATTEMPTS = 3;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final UrlMappingDynamoRepository urlMappingRepository;
    private final AnalyticsStorage analyticsStorage;
    private final AnalyticsPublisher analyticsPublisher;
    private final CacheService cacheService;
    private final UrlShortenerMetrics metrics;
    private final IpHasher ipHasher;
    private final SnowflakeIdGenerator idGenerator;
    private final PlanService planService;
    private final GuestQuotaService guestQuotaService;

    public UrlMappingService(UrlMappingDynamoRepository urlMappingRepository,
                             AnalyticsStorage analyticsStorage,
                             AnalyticsPublisher analyticsPublisher,
                             CacheService cacheService,
                             UrlShortenerMetrics metrics,
                             IpHasher ipHasher,
                             SnowflakeIdGenerator idGenerator,
                             PlanService planService,
                             GuestQuotaService guestQuotaService) {
        this.urlMappingRepository = urlMappingRepository;
        this.analyticsStorage = analyticsStorage;
        this.analyticsPublisher = analyticsPublisher;
        this.cacheService = cacheService;
        this.metrics = metrics;
        this.ipHasher = ipHasher;
        this.idGenerator = idGenerator;
        this.planService = planService;
        this.guestQuotaService = guestQuotaService;
    }

    // ============================================================
    // CREATE
    // ============================================================

    /**
     * Creates a new short URL for a signed-in user. Returns the user's
     * existing active link if they already shortened this URL; otherwise
     * fails if the plan's link limit is reached.
     */
    public UrlMappingDto createShortUrl(String originalUrl, User user) {
        var sample = metrics.startTimer();
        Plan plan = planService.effectivePlan(user);

        metrics.incrementDynamoRead();
        Optional<UrlMappingItem> existing = urlMappingRepository
                .findByOwnerAndOriginalUrl(user.getId(), originalUrl).stream()
                .filter(item -> isActive(item, plan))
                .findFirst();
        if (existing.isPresent()) {
            metrics.stopUrlCreationTime(sample);
            return convertToDto(existing.get(), plan);
        }

        long active = countActiveUrls(user, plan);
        if (active >= plan.maxActiveUrls()) {
            throw LimitExceededException.forbidden("You have reached the limit of " + plan.maxActiveUrls()
                    + " active links on the " + plan.displayName() + " plan.");
        }

        UrlMappingItem item = newItem(originalUrl);
        item.setOwnerId(user.getId());
        item.setOwnerUrlKey(UrlMappingItem.ownerUrlKey(user.getId(), originalUrl));
        item.setOwnerUsername(user.getUsername());
        save(item);

        metrics.stopUrlCreationTime(sample);
        return convertToDto(item, plan);
    }

    /**
     * Creates a short URL for a visitor without an account, within the guest
     * limits (one active link per browser, a daily cap per IP). Guest links
     * have no owner, so they cannot be listed, edited or deleted.
     */
    public UrlMappingDto createGuestShortUrl(String originalUrl, String guestId, String ipAddress) {
        var sample = metrics.startTimer();
        guestQuotaService.acquire(guestId, ipAddress);
        try {
            UrlMappingItem item = newItem(originalUrl);
            save(item);
            metrics.stopUrlCreationTime(sample);
            return convertToDto(item, Plan.GUEST);
        } catch (RuntimeException e) {
            guestQuotaService.release(guestId);
            throw e;
        }
    }

    private UrlMappingItem newItem(String originalUrl) {
        UrlMappingItem item = new UrlMappingItem();
        item.setOriginalUrl(originalUrl);
        item.setClickCount(0);
        String now = planService.now().format(ISO);
        item.setCreatedDate(now);
        item.setUpdatedDate(now);
        return item;
    }

    private void save(UrlMappingItem item) {
        metrics.incrementDynamoWrite();
        saveWithUniqueShortUrl(item);
        // Best-effort: if Redis is unavailable, the cache is filled on the next redirect.
        cacheService.put(item.getShortUrl(), CachedUrlData.from(item));
        metrics.incrementUrlCreation();
    }

    private void saveWithUniqueShortUrl(UrlMappingItem item) {
        for (int attempt = 0; attempt < MAX_SHORT_URL_ATTEMPTS; attempt++) {
            // One Snowflake ID serves as both the numeric id used by the REST
            // {id} endpoints and, base62-encoded, the short code.
            long id = idGenerator.nextId();
            item.setId(id);
            item.setShortUrl(Base62.encode(id));
            if (urlMappingRepository.saveIfNotExists(item)) {
                return;
            }
        }
        throw new IllegalStateException("Could not allocate a unique short URL");
    }

    // ============================================================
    // PLAN HELPERS
    // ============================================================

    /** Links the user has that have not expired under their current plan. */
    public long countActiveUrls(User user) {
        return countActiveUrls(user, planService.effectivePlan(user));
    }

    private long countActiveUrls(User user, Plan plan) {
        return urlMappingRepository.countByOwnerId(user.getId(), activeSince(plan));
    }

    /** ISO date-time before which links are expired under plan; null if they never expire. */
    private String activeSince(Plan plan) {
        LocalDateTime since = planService.activeLinksCreatedAfter(plan);
        return since != null ? since.format(ISO) : null;
    }

    private boolean isActive(UrlMappingItem item, Plan plan) {
        return planService.isLinkActive(parse(item.getCreatedDate()), plan);
    }

    private void requireAnalytics(User user) {
        if (!planService.effectivePlan(user).analytics()) {
            throw LimitExceededException.forbidden("Analytics is available on the Monthly and Yearly plans.");
        }
    }

    private static LocalDateTime parse(String isoDateTime) {
        return isoDateTime != null ? LocalDateTime.parse(isoDateTime, ISO) : null;
    }

    private UrlMappingDto convertToDto(UrlMappingItem item, Plan plan) {
        UrlMappingDto dto = new UrlMappingDto();
        dto.setId(item.getId());
        dto.setOriginalUrl(item.getOriginalUrl());
        dto.setShortUrl(item.getShortUrl());
        dto.setClickCount(clickCountOf(item));
        dto.setCreatedDate(parse(item.getCreatedDate()));
        dto.setUsername(item.getOwnerUsername());
        dto.setExpiresAt(planService.linkExpiresAt(dto.getCreatedDate(), plan));
        return dto;
    }

    private int clickCountOf(UrlMappingItem item) {
        return item.getClickCount() != null ? item.getClickCount() : 0;
    }

    // ============================================================
    // LIST (history)
    // ============================================================

    /**
     * Returns one page of a user's active URL mappings, ordered by creation
     * date. Expired links are left out. The cursor comes from the previous
     * page's nextCursor (null for the first page) and is only valid for the
     * sort direction it was issued with.
     */
    public CursorPageDto<UrlMappingDto> getUrlsByUser(User user, int size, String cursor, boolean ascending) {
        Plan plan = planService.effectivePlan(user);
        String since = activeSince(plan);
        UrlMappingDynamoRepository.DynamoPageResult<UrlMappingItem> result =
                urlMappingRepository.findByOwnerIdPaginated(
                        user.getId(), size, PageCursor.decode(cursor), ascending, since);

        List<UrlMappingDto> dtos = result.getItems().stream()
                .map(item -> convertToDto(item, plan))
                .toList();

        long total = urlMappingRepository.countByOwnerId(user.getId(), since);

        return new CursorPageDto<>(dtos, PageCursor.encode(result.getLastEvaluatedKey()), size, total);
    }

    // ============================================================
    // REDIRECT (hot path)
    // ============================================================

    /**
     * Resolves a short URL. Returns empty when the code does not exist or
     * its link has expired under the owner's current plan.
     *
     * CACHE-ASIDE PATTERN:
     *   1. Look up Redis first. A hit needs no DynamoDB call.
     *   2. Skip DynamoDB if this code was recently found not to exist.
     *   3. Otherwise read from DynamoDB and populate Redis.
     *   4. If Redis is unavailable, every lookup falls back to DynamoDB.
     *
     * Click events are published only for plans with analytics; clickCount
     * is updated by the Kafka consumer, not here.
     */
    public Optional<RedirectTarget> resolve(String shortUrl, String ipAddress, String referrer, String userAgent) {
        var totalSample = metrics.startTimer();
        metrics.incrementRedirectRequest();

        var redisSample = metrics.startTimer();
        Optional<CachedUrlData> cached = cacheService.get(shortUrl);
        metrics.stopRedisLookupTime(redisSample);

        String originalUrl;
        Long ownerId;
        String createdDate;
        if (cached.isPresent()) {
            metrics.incrementCacheHit();
            originalUrl = cached.get().getOriginalUrl();
            ownerId = cached.get().getOwnerId();
            createdDate = cached.get().getCreatedDate();
        } else {
            metrics.incrementCacheMiss();
            if (cacheService.isMarkedMissing(shortUrl)) {
                return notFound(totalSample);
            }

            metrics.incrementDynamoRead();
            var dynamoSample = metrics.startTimer();
            Optional<UrlMappingItem> optItem = urlMappingRepository.findByShortUrl(shortUrl);
            metrics.stopDynamoLookupTime(dynamoSample);

            if (optItem.isEmpty()) {
                cacheService.markMissing(shortUrl);
                return notFound(totalSample);
            }
            UrlMappingItem item = optItem.get();
            cacheService.put(shortUrl, CachedUrlData.from(item));
            originalUrl = item.getOriginalUrl();
            ownerId = item.getOwnerId();
            createdDate = item.getCreatedDate();
        }

        Plan plan = planService.effectivePlanForOwner(ownerId);
        LocalDateTime expiresAt = planService.linkExpiresAt(parse(createdDate), plan);
        LocalDateTime now = planService.now();
        if (expiresAt != null && !now.isBefore(expiresAt)) {
            // Not marked missing: the owner may upgrade, which revives it.
            return notFound(totalSample);
        }

        if (plan.analytics()) {
            logClickEvent(shortUrl, ipAddress, referrer, userAgent);
        }
        metrics.stopRedirectTotalTime(totalSample);
        metrics.incrementRedirectSuccess();

        Long maxAge = expiresAt != null ? Duration.between(now, expiresAt).toSeconds() : null;
        return Optional.of(new RedirectTarget(originalUrl, plan.permanentRedirect(), maxAge));
    }

    private Optional<RedirectTarget> notFound(io.micrometer.core.instrument.Timer.Sample totalSample) {
        metrics.stopRedirectTotalTime(totalSample);
        metrics.incrementRedirectFailure();
        return Optional.empty();
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

    // ============================================================
    // SINGLE LINK: read / update / delete
    // ============================================================

    /**
     * Finds a URL mapping by its numeric ID, verifying user ownership.
     */
    public UrlMappingDto getUrlByIdAndUser(Long id, User user) {
        Plan plan = planService.effectivePlan(user);
        return convertToDto(findOwnedItem(id, user, plan), plan);
    }

    /** The user's link with this id; expired links count as not found. */
    private UrlMappingItem findOwnedItem(Long id, User user, Plan plan) {
        return urlMappingRepository.findByIdAndOwnerId(id, user.getId())
                .filter(item -> isActive(item, plan))
                .orElseThrow(() -> new UrlMappingNotFoundException("URL mapping not found or access denied"));
    }

    /**
     * Updates the original URL for a given mapping. Only the changed attributes
     * are written, so concurrent click count increments are not lost.
     */
    public UrlMappingDto updateUrl(Long id, String newOriginalUrl, User user) {
        Plan plan = planService.effectivePlan(user);
        UrlMappingItem item = findOwnedItem(id, user, plan);

        if (newOriginalUrl != null && !newOriginalUrl.isBlank()) {
            String now = planService.now().format(ISO);
            String ownerUrlKey = UrlMappingItem.ownerUrlKey(user.getId(), newOriginalUrl);
            if (!urlMappingRepository.updateOriginalUrl(item.getShortUrl(), newOriginalUrl, ownerUrlKey, now)) {
                throw new UrlMappingNotFoundException("URL mapping not found or access denied");
            }
            item.setOriginalUrl(newOriginalUrl);
            item.setOwnerUrlKey(ownerUrlKey);
            item.setUpdatedDate(now);

            // Best-effort: on failure the old target is served until the TTL expires.
            cacheService.put(item.getShortUrl(), CachedUrlData.from(item));
        }

        return convertToDto(item, plan);
    }

    /**
     * Deletes a URL mapping and its associated click events.
     *
     * NOTE: This is a cross-database operation with no distributed transaction.
     * The mapping is deleted first so that a MongoDB failure leaves orphaned
     * analytics rather than a live link with no history.
     */
    public void deleteUrl(Long id, User user) {
        UrlMappingItem item = findOwnedItem(id, user, planService.effectivePlan(user));

        urlMappingRepository.deleteByShortUrl(item.getShortUrl());

        // Best-effort: on failure the stale entry expires via its TTL.
        cacheService.evict(item.getShortUrl());
        analyticsStorage.deleteByShortUrl(item.getShortUrl());
        metrics.incrementUrlDeletion();
    }

    // ============================================================
    // ANALYTICS (paid plans only)
    // ============================================================

    /**
     * Returns click event counts grouped by date for a specific URL.
     */
    public List<ClickEventDto> getClickEventsByDate(String shortUrl, LocalDateTime start,
                                                     LocalDateTime end, User user) {
        requireAnalytics(user);
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
        requireAnalytics(user);
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
     * Returns detailed click events for a specific URL within a date range.
     */
    public List<ClickEventDetailDto> getClickEventDetails(String shortUrl, LocalDateTime start,
                                                           LocalDateTime end, User user) {
        requireAnalytics(user);
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
        requireAnalytics(user);
        Plan plan = planService.effectivePlan(user);
        metrics.incrementAnalyticsRequest();
        List<UrlMappingItem> userUrls = urlMappingRepository.findByOwnerId(user.getId());

        long totalUrls = userUrls.size();
        long totalClicks = userUrls.stream().mapToLong(this::clickCountOf).sum();

        List<String> shortUrls = shortUrlsOf(userUrls);

        LocalDateTime todayStart = planService.now().toLocalDate().atStartOfDay();
        LocalDateTime todayEnd = todayStart.plusDays(1);
        long clicksToday = analyticsStorage.countByShortUrlsAndDateBetween(shortUrls, todayStart, todayEnd);

        LocalDateTime weekStart = todayStart.minusDays(7);
        long clicksThisWeek = analyticsStorage.countByShortUrlsAndDateBetween(shortUrls, weekStart, todayEnd);

        List<UrlMappingDto> topUrls = topByTotalClicks(userUrls, 5, plan);

        return new DashboardDto(totalUrls, totalClicks, clicksToday, clicksThisWeek, topUrls);
    }

    /**
     * Returns the top N URLs by total click count.
     */
    public List<UrlMappingDto> getTopUrls(User user, int limit) {
        requireAnalytics(user);
        return topByTotalClicks(urlMappingRepository.findByOwnerId(user.getId()), limit,
                planService.effectivePlan(user));
    }

    private List<UrlMappingDto> topByTotalClicks(List<UrlMappingItem> items, int limit, Plan plan) {
        return items.stream()
                .sorted(Comparator.comparingInt(this::clickCountOf).reversed())
                .limit(limit)
                .map(item -> convertToDto(item, plan))
                .toList();
    }

    /**
     * Returns the top N URLs by click count within a date range.
     * Each returned clickCount is the count for that range, not the all-time total.
     */
    public List<UrlMappingDto> getTopUrlsByDate(User user, int limit, LocalDate start, LocalDate end) {
        requireAnalytics(user);
        Plan plan = planService.effectivePlan(user);
        metrics.incrementAnalyticsRequest();
        List<UrlMappingItem> userUrls = urlMappingRepository.findByOwnerId(user.getId());
        Map<String, Long> counts = analyticsStorage.countPerShortUrlAndDateBetween(
                shortUrlsOf(userUrls), start.atStartOfDay(), end.plusDays(1).atStartOfDay());

        return userUrls.stream()
                .sorted(Comparator.comparingLong(
                        (UrlMappingItem item) -> counts.getOrDefault(item.getShortUrl(), 0L)).reversed())
                .limit(limit)
                .map(item -> {
                    UrlMappingDto dto = convertToDto(item, plan);
                    dto.setClickCount(counts.getOrDefault(item.getShortUrl(), 0L).intValue());
                    return dto;
                })
                .toList();
    }
}
