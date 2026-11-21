package com.Url_Shortener.plan;

import com.Url_Shortener.cache.CacheService;
import com.Url_Shortener.model.UrlMappingItem;
import com.Url_Shortener.repository.UrlMappingDynamoRepository;
import com.Url_Shortener.storage.AnalyticsStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Once a day, applies the data-retention rules to every link:
 *
 * <ul>
 *   <li>Deletes links that expired more than PlanService.EXPIRED_DATA_RETENTION
 *       ago, with all their click data. This is when "gone for good" becomes
 *       literal, and when a custom code becomes free for others.</li>
 *   <li>Deletes individual clicks older than the owner's plan keeps them
 *       (Plan.clickDetailRetention). Daily totals are kept.</li>
 * </ul>
 *
 * Reads the whole links table. Safe to run on several instances at once or
 * repeatedly: every step is idempotent. Disable with app.cleanup.enabled=false.
 */
@Component
@ConditionalOnProperty(name = "app.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class LinkCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(LinkCleanupJob.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final UrlMappingDynamoRepository urlMappingRepository;
    private final AnalyticsStorage analyticsStorage;
    private final CacheService cacheService;
    private final PlanService planService;

    public LinkCleanupJob(UrlMappingDynamoRepository urlMappingRepository, AnalyticsStorage analyticsStorage,
                          CacheService cacheService, PlanService planService) {
        this.urlMappingRepository = urlMappingRepository;
        this.analyticsStorage = analyticsStorage;
        this.cacheService = cacheService;
        this.planService = planService;
    }

    /** Counts from one run. */
    public record Result(int linksChecked, int linksDeleted, long clicksTrimmed, int failures) {
    }

    @Scheduled(cron = "${app.cleanup.cron:0 30 3 * * *}")
    public void scheduledRun() {
        Result result = run();
        log.info("Link cleanup: checked {} links, deleted {}, trimmed {} clicks, {} failures",
                result.linksChecked(), result.linksDeleted(), result.clicksTrimmed(), result.failures());
    }

    public Result run() {
        LocalDateTime now = planService.now();
        Map<Long, OwnerStatus> owners = new HashMap<>();
        int[] checked = {0};
        int[] deleted = {0};
        int[] failures = {0};
        long[] trimmed = {0};

        urlMappingRepository.forEach(item -> {
            checked[0]++;
            try {
                OwnerStatus owner = item.getOwnerId() == null
                        ? OwnerStatus.GUEST
                        : owners.computeIfAbsent(item.getOwnerId(), planService::ownerStatus);
                LocalDateTime expiresAt = planService.linkExpiresAt(
                        parse(item.getCreatedDate()), parse(item.getExpiresAt()), owner);

                LocalDateTime deleteAt = planService.dataDeletedAt(expiresAt);
                if (deleteAt != null && !now.isBefore(deleteAt)) {
                    delete(item);
                    deleted[0]++;
                    return;
                }

                // An expired link is kept on the terms of a plan without analytics.
                Plan retention = expiresAt != null && !now.isBefore(expiresAt)
                        ? (owner.effective() == Plan.GUEST ? Plan.GUEST : Plan.FREE)
                        : owner.effective();
                trimmed[0] += analyticsStorage.deleteClickDetailsBefore(
                        item.getShortUrl(), now.minus(retention.clickDetailRetention()));
            } catch (Exception e) {
                failures[0]++;
                log.warn("Cleanup failed for link '{}': {}", item.getShortUrl(), e.getMessage());
            }
        });
        return new Result(checked[0], deleted[0], trimmed[0], failures[0]);
    }

    private void delete(UrlMappingItem item) {
        // Link first, as in UrlMappingService.deleteUrl: a failure after this
        // leaves orphaned clicks rather than a link with missing history.
        urlMappingRepository.deleteByShortUrl(item.getShortUrl());
        cacheService.evict(item.getShortUrl());
        analyticsStorage.deleteByShortUrl(item.getShortUrl());
    }

    private static LocalDateTime parse(String isoDateTime) {
        return isoDateTime != null ? LocalDateTime.parse(isoDateTime, ISO) : null;
    }
}
