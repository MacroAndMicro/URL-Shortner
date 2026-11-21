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
import com.Url_Shortener.exception.ConflictException;
import com.Url_Shortener.exception.LimitExceededException;
import com.Url_Shortener.exception.UrlMappingNotFoundException;
import com.Url_Shortener.metrics.UrlShortenerMetrics;
import com.Url_Shortener.model.UrlMappingItem;
import com.Url_Shortener.plan.GuestQuotaService;
import com.Url_Shortener.plan.OwnerStatus;
import com.Url_Shortener.plan.Plan;
import com.Url_Shortener.plan.PlanService;
import com.Url_Shortener.publisher.AnalyticsPublisher;
import com.Url_Shortener.repository.UrlMappingDynamoRepository;
import com.Url_Shortener.repository.UserRepository;
import com.Url_Shortener.security.IpHasher;
import com.Url_Shortener.shortcode.Base62;
import com.Url_Shortener.shortcode.ShortCode;
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
import java.util.Locale;
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
 * Plan rules (see Plan and PlanService) are applied here: link limits,
 * link expiry, edit window, custom codes, 301 vs 302 redirects, and who
 * sees analytics. Expired links stop resolving and are hidden at once;
 * LinkCleanupJob deletes their data later.
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
    private final UserRepository userRepository;

    public UrlMappingService(UrlMappingDynamoRepository urlMappingRepository,
                             AnalyticsStorage analyticsStorage,
                             AnalyticsPublisher analyticsPublisher,
                             CacheService cacheService,
                             UrlShortenerMetrics metrics,
                             IpHasher ipHasher,
                             SnowflakeIdGenerator idGenerator,
                             PlanService planService,
                             GuestQuotaService guestQuotaService,
                             UserRepository userRepository) {
        this.urlMappingRepository = urlMappingRepository;
        this.analyticsStorage = analyticsStorage;
        this.analyticsPublisher = analyticsPublisher;
        this.cacheService = cacheService;
        this.metrics = metrics;
        this.ipHasher = ipHasher;
        this.idGenerator = idGenerator;
        this.planService = planService;
        this.guestQuotaService = guestQuotaService;
        this.userRepository = userRepository;
    }

    // ============================================================
    // CREATE
    // ============================================================

    public UrlMappingDto createShortUrl(String originalUrl, User user) {
        return createShortUrl(originalUrl, null, user);
    }

    /**
     * Creates a short URL for a signed-in user, with a chosen code when
     * customCode is given (plans with custom codes only). Without one, returns
     * the user's existing working link for the same URL if there is one.
     * Fails if the plan's link limit is reached.
     */
    public UrlMappingDto createShortUrl(String originalUrl, String customCode, User user) {
        var sample = metrics.startTimer();
        OwnerStatus status = planService.status(user);
        Plan plan = status.effective();

        String code = null;
        if (customCode != null && !customCode.isBlank()) {
            if (!plan.customCodes()) {
                throw LimitExceededException.forbidden("Custom URLs are available on the Yearly plan.");
            }
            code = ShortCode.normalizeCustom(customCode);
        } else {
            metrics.incrementDynamoRead();
            Optional<UrlMappingItem> existing = urlMappingRepository
                    .findByOwnerAndOriginalUrl(user.getId(), originalUrl).stream()
                    .filter(item -> isActive(item, status))
                    .findFirst();
            if (existing.isPresent()) {
                metrics.stopUrlCreationTime(sample);
                return convertToDto(existing.get(), status);
            }
        }

        LocalDateTime periodStart = reserveLink(user, status);
        try {
            UrlMappingItem item = newItem(originalUrl, plan);
            item.setOwnerId(user.getId());
            item.setOwnerUsername(user.getUsername());
            if (code == null) {
                item.setOwnerUrlKey(UrlMappingItem.ownerUrlKey(user.getId(), originalUrl));
                save(item);
            } else {
                // Custom links skip the duplicate check, so they are not indexed for it.
                saveCustom(item, code);
            }
            metrics.stopUrlCreationTime(sample);
            return convertToDto(item, status);
        } catch (RuntimeException e) {
            if (periodStart != null) {
                userRepository.releasePeriodLink(user.getId(), periodStart);
            }
            throw e;
        }
    }

    /**
     * Checks the plan's link limit, and for per-period limits counts the new
     * link. Returns the period start counted against, or null if nothing was
     * counted (so nothing needs giving back on failure).
     */
    private LocalDateTime reserveLink(User user, OwnerStatus status) {
        Plan plan = status.effective();
        if (plan.limitPeriod() == Plan.LimitPeriod.ACTIVE) {
            if (countActiveUrls(user, status) >= plan.linkLimit()) {
                throw LimitExceededException.forbidden("You have reached the limit of " + plan.linkLimit()
                        + " active links on the " + plan.displayName() + " plan.");
            }
            return null;
        }
        LocalDateTime periodStart = planService.periodStart(status);
        if (userRepository.reservePeriodLink(user.getId(), periodStart, plan.linkLimit()) == 0) {
            String period = plan.limitPeriod().name().toLowerCase(Locale.ROOT);
            throw LimitExceededException.forbidden("You have created " + plan.linkLimit()
                    + " links this " + period + ", the limit of the " + plan.displayName()
                    + " plan. More become available when your plan renews.");
        }
        return periodStart;
    }

    /**
     * Creates a short URL for a visitor without an account, within the guest
     * limits (one working link per browser, a daily cap per IP). Guest links
     * have no owner, so they cannot be listed, edited or deleted.
     */
    public UrlMappingDto createGuestShortUrl(String originalUrl, String customCode, String guestId,
                                             String ipAddress) {
        if (customCode != null && !customCode.isBlank()) {
            throw LimitExceededException.forbidden("Custom URLs are available on the Yearly plan.");
        }
        var sample = metrics.startTimer();
        guestQuotaService.acquire(guestId, ipAddress);
        try {
            UrlMappingItem item = newItem(originalUrl, Plan.GUEST);
            save(item);
            metrics.stopUrlCreationTime(sample);
            return convertToDto(item, OwnerStatus.GUEST);
        } catch (RuntimeException e) {
            guestQuotaService.release(guestId);
            throw e;
        }
    }

    private UrlMappingItem newItem(String originalUrl, Plan plan) {
        UrlMappingItem item = new UrlMappingItem();
        item.setOriginalUrl(originalUrl);
        item.setClickCount(0);
        String now = planService.now().format(ISO);
        item.setCreatedDate(now);
        item.setUpdatedDate(now);
        item.setExpiresAt(format(planService.newLinkExpiresAt(plan)));
        return item;
    }

    private void save(UrlMappingItem item) {
        metrics.incrementDynamoWrite();
        saveWithUniqueShortUrl(item);
        cached(item);
    }

    private void saveCustom(UrlMappingItem item, String code) {
        item.setId(idGenerator.nextId());
        item.setShortUrl(code);
        metrics.incrementDynamoWrite();
        if (!urlMappingRepository.saveIfNotExists(item)) {
            throw new ConflictException("The custom URL '" + code + "' is already taken.");
        }
        cached(item);
    }

    private void cached(UrlMappingItem item) {
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

    /** Links the user has that still work. */
    public long countActiveUrls(User user) {
        return countActiveUrls(user, planService.status(user));
    }

    private long countActiveUrls(User user, OwnerStatus status) {
        return urlMappingRepository.countByOwnerId(user.getId(),
                format(planService.activeLinksCreatedAfter(status)), format(planService.now()));
    }

    /** Links created in the current paid period; 0 when not on a paid plan. */
    public int linksCreatedThisPeriod(User user) {
        LocalDateTime periodStart = planService.periodStart(planService.status(user));
        return periodStart != null && periodStart.equals(user.getPeriodLinksStart())
                ? user.getPeriodLinksUsed() : 0;
    }

    private LocalDateTime expiresAt(UrlMappingItem item, OwnerStatus owner) {
        return planService.linkExpiresAt(parse(item.getCreatedDate()), parse(item.getExpiresAt()), owner);
    }

    private boolean isActive(UrlMappingItem item, OwnerStatus owner) {
        return planService.isActive(expiresAt(item, owner));
    }

    private void requireAnalytics(User user) {
        if (!planService.effectivePlan(user).analytics()) {
            throw LimitExceededException.forbidden("Analytics is available on the Monthly and Yearly plans.");
        }
    }

    /** The user's links that still work. For analytics, which only paid plans see. */
    private List<UrlMappingItem> activeUrlsOf(User user, OwnerStatus status) {
        return urlMappingRepository.findByOwnerId(user.getId()).stream()
                .filter(item -> isActive(item, status))
                .toList();
    }

    private static LocalDateTime parse(String isoDateTime) {
        return isoDateTime != null ? LocalDateTime.parse(isoDateTime, ISO) : null;
    }

    private static String format(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.format(ISO) : null;
    }

    private UrlMappingDto convertToDto(UrlMappingItem item, OwnerStatus owner) {
        UrlMappingDto dto = new UrlMappingDto();
        dto.setId(item.getId());
        dto.setOriginalUrl(item.getOriginalUrl());
        dto.setShortUrl(item.getShortUrl());
        dto.setClickCount(clickCountOf(item));
        dto.setCreatedDate(parse(item.getCreatedDate()));
        dto.setUsername(item.getOwnerUsername());
        dto.setExpiresAt(expiresAt(item, owner));
        Duration editWindow = owner.effective().editWindow();
        dto.setEditableUntil(editWindow != null && dto.getCreatedDate() != null
                ? dto.getCreatedDate().plus(editWindow) : null);
        return dto;
    }

    private int clickCountOf(UrlMappingItem item) {
        return item.getClickCount() != null ? item.getClickCount() : 0;
    }

    // ============================================================
    // LIST (history)
    // ============================================================

    /**
     * Returns one page of the user's working links, ordered by creation date.
     * The cursor comes from the previous page's nextCursor (null for the first
     * page) and is only valid for the sort direction it was issued with.
     */
    public CursorPageDto<UrlMappingDto> getUrlsByUser(User user, int size, String cursor, boolean ascending) {
        OwnerStatus status = planService.status(user);
        String since = format(planService.activeLinksCreatedAfter(status));
        String now = format(planService.now());
        UrlMappingDynamoRepository.DynamoPageResult<UrlMappingItem> result =
                urlMappingRepository.findByOwnerIdPaginated(
                        user.getId(), size, PageCursor.decode(cursor), ascending, since, now);

        List<UrlMappingDto> dtos = result.getItems().stream()
                .filter(item -> isActive(item, status))
                .map(item -> convertToDto(item, status))
                .toList();

        long total = urlMappingRepository.countByOwnerId(user.getId(), since, now);

        return new CursorPageDto<>(dtos, PageCursor.encode(result.getLastEvaluatedKey()), size, total);
    }

    // ============================================================
    // REDIRECT (hot path)
    // ============================================================

    /**
     * Resolves a short URL. Returns empty when the code does not exist or
     * its link has expired.
     *
     * Generated codes are case sensitive; custom codes are stored in lower
     * case, so a code that is not found as typed is tried in lower case.
     *
     * CACHE-ASIDE PATTERN:
     *   1. Look up Redis first. A hit needs no DynamoDB call.
     *   2. Skip DynamoDB if this code was recently found not to exist.
     *   3. Otherwise read from DynamoDB and populate Redis.
     *   4. If Redis is unavailable, every lookup falls back to DynamoDB.
     *
     * Clicks are recorded for every plan; only paid plans are shown them.
     * clickCount is updated by the Kafka consumer, not here.
     */
    public Optional<RedirectTarget> resolve(String shortUrl, String ipAddress, String referrer, String userAgent) {
        var totalSample = metrics.startTimer();
        metrics.incrementRedirectRequest();

        Optional<CachedUrlData> found = lookup(shortUrl);
        String lowerCase = shortUrl.toLowerCase(Locale.ROOT);
        if (found.isEmpty() && !lowerCase.equals(shortUrl)) {
            found = lookup(lowerCase);
        }
        if (found.isEmpty()) {
            return notFound(totalSample);
        }
        CachedUrlData link = found.get();

        OwnerStatus owner = planService.ownerStatus(link.getOwnerId());
        LocalDateTime expiresAt = planService.linkExpiresAt(
                parse(link.getCreatedDate()), parse(link.getExpiresAt()), owner);
        LocalDateTime now = planService.now();
        if (expiresAt != null && !now.isBefore(expiresAt)) {
            return notFound(totalSample);
        }

        logClickEvent(link.getShortUrl(), ipAddress, referrer, userAgent);
        metrics.stopRedirectTotalTime(totalSample);
        metrics.incrementRedirectSuccess();

        Long maxAge = expiresAt != null ? Duration.between(now, expiresAt).toSeconds() : null;
        return Optional.of(new RedirectTarget(link.getOriginalUrl(), owner.effective().permanentRedirect(), maxAge));
    }

    /** The link stored under exactly this code, from Redis or DynamoDB. */
    private Optional<CachedUrlData> lookup(String code) {
        var redisSample = metrics.startTimer();
        Optional<CachedUrlData> cached = cacheService.get(code);
        metrics.stopRedisLookupTime(redisSample);
        if (cached.isPresent()) {
            metrics.incrementCacheHit();
            return cached;
        }
        metrics.incrementCacheMiss();
        if (cacheService.isMarkedMissing(code)) {
            return Optional.empty();
        }

        metrics.incrementDynamoRead();
        var dynamoSample = metrics.startTimer();
        Optional<UrlMappingItem> item = urlMappingRepository.findByShortUrl(code);
        metrics.stopDynamoLookupTime(dynamoSample);
        if (item.isEmpty()) {
            cacheService.markMissing(code);
            return Optional.empty();
        }
        CachedUrlData data = CachedUrlData.from(item.get());
        cacheService.put(code, data);
        return Optional.of(data);
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
        OwnerStatus status = planService.status(user);
        return convertToDto(findOwnedItem(id, user, status), status);
    }

    /** The user's link with this id; expired links count as not found. */
    private UrlMappingItem findOwnedItem(Long id, User user, OwnerStatus status) {
        return urlMappingRepository.findByIdAndOwnerId(id, user.getId())
                .filter(item -> isActive(item, status))
                .orElseThrow(() -> new UrlMappingNotFoundException("URL mapping not found or access denied"));
    }

    /**
     * Changes where a link points, within the plan's edit window. Only the
     * changed attributes are written, so concurrent click count increments
     * are not lost.
     */
    public UrlMappingDto updateUrl(Long id, String newOriginalUrl, User user) {
        OwnerStatus status = planService.status(user);
        UrlMappingItem item = findOwnedItem(id, user, status);

        Duration editWindow = status.effective().editWindow();
        if (editWindow != null && !planService.now().isBefore(parse(item.getCreatedDate()).plus(editWindow))) {
            throw LimitExceededException.forbidden(status.effective().displayName() + " links can be changed within "
                    + editWindow.toHours() + " hours of creation. You can still delete this link.");
        }

        if (newOriginalUrl != null && !newOriginalUrl.isBlank()) {
            String now = planService.now().format(ISO);
            String ownerUrlKey = item.getOwnerUrlKey() != null
                    ? UrlMappingItem.ownerUrlKey(user.getId(), newOriginalUrl)
                    : null;  // custom links stay out of the duplicate check
            if (!urlMappingRepository.updateOriginalUrl(item.getShortUrl(), newOriginalUrl, ownerUrlKey, now)) {
                throw new UrlMappingNotFoundException("URL mapping not found or access denied");
            }
            item.setOriginalUrl(newOriginalUrl);
            item.setOwnerUrlKey(ownerUrlKey);
            item.setUpdatedDate(now);

            // Best-effort: on failure the old target is served until the TTL expires.
            cacheService.put(item.getShortUrl(), CachedUrlData.from(item));
        }

        return convertToDto(item, status);
    }

    /**
     * Deletes a URL mapping and its associated click data. Allowed on every
     * plan at any time. For per-period limits, the link stays counted.
     *
     * NOTE: This is a cross-database operation with no distributed transaction.
     * The mapping is deleted first so that a MongoDB failure leaves orphaned
     * analytics rather than a live link with no history.
     */
    public void deleteUrl(Long id, User user) {
        UrlMappingItem item = findOwnedItem(id, user, planService.status(user));

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
     * Returns click counts per day for a specific URL.
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
        List<String> shortUrls = shortUrlsOf(activeUrlsOf(user, planService.status(user)));

        return analyticsStorage.findAggregatedByShortUrlsAndDateBetween(
                shortUrls, start.atStartOfDay(), end.plusDays(1).atStartOfDay());
    }

    private List<String> shortUrlsOf(List<UrlMappingItem> items) {
        return items.stream()
                .map(UrlMappingItem::getShortUrl)
                .collect(Collectors.toList());
    }

    /**
     * Returns individual clicks on a specific URL within a date range, as far
     * back as the plan keeps them.
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
     * Aggregates data from DynamoDB (URL metadata) and MongoDB (click totals).
     */
    public DashboardDto getDashboard(User user) {
        requireAnalytics(user);
        OwnerStatus status = planService.status(user);
        metrics.incrementAnalyticsRequest();
        List<UrlMappingItem> userUrls = activeUrlsOf(user, status);

        long totalUrls = userUrls.size();
        long totalClicks = userUrls.stream().mapToLong(this::clickCountOf).sum();

        List<String> shortUrls = shortUrlsOf(userUrls);

        LocalDateTime todayStart = planService.now().toLocalDate().atStartOfDay();
        LocalDateTime todayEnd = todayStart.plusDays(1);
        long clicksToday = analyticsStorage.countByShortUrlsAndDateBetween(shortUrls, todayStart, todayEnd);

        LocalDateTime weekStart = todayStart.minusDays(6); // the last 7 days, today included
        long clicksThisWeek = analyticsStorage.countByShortUrlsAndDateBetween(shortUrls, weekStart, todayEnd);

        List<UrlMappingDto> topUrls = topByTotalClicks(userUrls, 5, status);

        return new DashboardDto(totalUrls, totalClicks, clicksToday, clicksThisWeek, topUrls);
    }

    /**
     * Returns the top N URLs by total click count.
     */
    public List<UrlMappingDto> getTopUrls(User user, int limit) {
        requireAnalytics(user);
        OwnerStatus status = planService.status(user);
        return topByTotalClicks(activeUrlsOf(user, status), limit, status);
    }

    private List<UrlMappingDto> topByTotalClicks(List<UrlMappingItem> items, int limit, OwnerStatus status) {
        return items.stream()
                .sorted(Comparator.comparingInt(this::clickCountOf).reversed())
                .limit(limit)
                .map(item -> convertToDto(item, status))
                .toList();
    }

    /**
     * Returns the top N URLs by click count within a date range.
     * Each returned clickCount is the count for that range, not the all-time total.
     */
    public List<UrlMappingDto> getTopUrlsByDate(User user, int limit, LocalDate start, LocalDate end) {
        requireAnalytics(user);
        OwnerStatus status = planService.status(user);
        metrics.incrementAnalyticsRequest();
        List<UrlMappingItem> userUrls = activeUrlsOf(user, status);
        Map<String, Long> counts = analyticsStorage.countPerShortUrlAndDateBetween(
                shortUrlsOf(userUrls), start.atStartOfDay(), end.plusDays(1).atStartOfDay());

        return userUrls.stream()
                .sorted(Comparator.comparingLong(
                        (UrlMappingItem item) -> counts.getOrDefault(item.getShortUrl(), 0L)).reversed())
                .limit(limit)
                .map(item -> {
                    UrlMappingDto dto = convertToDto(item, status);
                    dto.setClickCount(counts.getOrDefault(item.getShortUrl(), 0L).intValue());
                    return dto;
                })
                .toList();
    }
}
