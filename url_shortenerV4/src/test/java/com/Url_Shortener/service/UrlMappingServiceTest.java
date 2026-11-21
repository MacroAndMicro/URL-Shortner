package com.Url_Shortener.service;

import com.Url_Shortener.cache.CacheService;
import com.Url_Shortener.cache.CachedUrlData;
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
import com.Url_Shortener.shortcode.Base62;
import com.Url_Shortener.shortcode.SnowflakeIdGenerator;
import com.Url_Shortener.storage.AnalyticsStorage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UrlMappingServiceTest {

    private static final String CODE = "QN7XOa0a";
    private static final String TARGET = "https://example.com";

    private UrlMappingDynamoRepository repository;
    private AnalyticsStorage analyticsStorage;
    private AnalyticsPublisher publisher;
    private CacheService cache;
    private UrlMappingService service;
    private User user;

    @BeforeEach
    void setUp() {
        repository = mock(UrlMappingDynamoRepository.class);
        analyticsStorage = mock(AnalyticsStorage.class);
        publisher = mock(AnalyticsPublisher.class);
        cache = mock(CacheService.class);
        service = new UrlMappingService(repository, analyticsStorage, publisher, cache,
                new UrlShortenerMetrics(new SimpleMeterRegistry()), new IpHasher("salt"),
                new SnowflakeIdGenerator(0));
        user = new User();
        user.setId(1L);
        user.setUsername("tester");
    }

    private UrlMappingItem item(String shortUrl, int clicks) {
        UrlMappingItem item = new UrlMappingItem();
        item.setShortUrl(shortUrl);
        item.setId(42L);
        item.setOriginalUrl(TARGET);
        item.setOwnerId(1L);
        item.setClickCount(clicks);
        return item;
    }

    @Test
    void cacheHitRedirectsWithoutTouchingDynamoDb() {
        when(cache.get(CODE)).thenReturn(Optional.of(CachedUrlData.from(item(CODE, 0))));

        UrlMapping result = service.getOriginalUrl(CODE, "1.2.3.4", null, "agent");

        assertThat(result.getOriginalUrl()).isEqualTo(TARGET);
        verifyNoInteractions(repository);
        verify(publisher).publish(any());
    }

    @Test
    void cacheMissReadsDynamoDbAndFillsCacheWithoutIncrementing() {
        when(cache.get(CODE)).thenReturn(Optional.empty());
        when(repository.findByShortUrl(CODE)).thenReturn(Optional.of(item(CODE, 0)));

        assertThat(service.getOriginalUrl(CODE, "1.2.3.4", null, "agent")).isNotNull();

        verify(cache).put(eq(CODE), any());
        verify(repository, never()).incrementClickCount(any());
    }

    @Test
    void unknownCodeIsMarkedMissingAndThenSkipsDynamoDb() {
        when(cache.get(CODE)).thenReturn(Optional.empty());
        when(repository.findByShortUrl(CODE)).thenReturn(Optional.empty());

        assertThat(service.getOriginalUrl(CODE, "1.2.3.4", null, "agent")).isNull();
        verify(cache).markMissing(CODE);
        verify(publisher, never()).publish(any());

        reset(repository);
        when(cache.isMarkedMissing(CODE)).thenReturn(true);

        assertThat(service.getOriginalUrl(CODE, "1.2.3.4", null, "agent")).isNull();
        verifyNoInteractions(repository);
    }

    @Test
    void publishedEventContainsHashedIpNotTheRawAddress() {
        when(cache.get(CODE)).thenReturn(Optional.of(CachedUrlData.from(item(CODE, 0))));

        service.getOriginalUrl(CODE, "1.2.3.4", null, "agent");

        ArgumentCaptor<ClickEventCreatedEvent> event = ArgumentCaptor.forClass(ClickEventCreatedEvent.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().getIpAddress()).isNotEqualTo("1.2.3.4").hasSize(64);
    }

    @Test
    void createUsesTheBase62SnowflakeIdAsTheShortCode() {
        when(repository.findByOwnerAndOriginalUrl(1L, TARGET)).thenReturn(Optional.empty());
        when(repository.saveIfNotExists(any())).thenReturn(true);

        UrlMappingDto dto = service.createShortUrl(TARGET, user);

        assertThat(dto.getShortUrl()).matches("[0-9A-Za-z]{10,11}");
        assertThat(Base62.decode(dto.getShortUrl())).isEqualTo(dto.getId());
    }

    @Test
    void createStoresTheDuplicateLookupKeyWithoutLoadingTheUsersUrls() {
        when(repository.findByOwnerAndOriginalUrl(1L, TARGET)).thenReturn(Optional.empty());
        when(repository.saveIfNotExists(any())).thenReturn(true);

        service.createShortUrl(TARGET, user);

        ArgumentCaptor<UrlMappingItem> saved = ArgumentCaptor.forClass(UrlMappingItem.class);
        verify(repository).saveIfNotExists(saved.capture());
        assertThat(saved.getValue().getOwnerUrlKey())
                .isEqualTo(UrlMappingItem.ownerUrlKey(1L, TARGET))
                .startsWith("1#")
                .hasSize(2 + 64);
        verify(repository, never()).findByOwnerId(any());
    }

    @Test
    void createRetriesWithANewCodeOnCollision() {
        when(repository.findByOwnerAndOriginalUrl(1L, TARGET)).thenReturn(Optional.empty());
        when(repository.saveIfNotExists(any())).thenReturn(false, true);

        ArgumentCaptor<UrlMappingItem> saved = ArgumentCaptor.forClass(UrlMappingItem.class);
        UrlMappingDto dto = service.createShortUrl(TARGET, user);

        verify(repository, times(2)).saveIfNotExists(saved.capture());
        assertThat(dto.getShortUrl()).matches("[0-9A-Za-z]{10,11}");
        assertThat(Base62.decode(dto.getShortUrl())).isEqualTo(dto.getId());
    }

    @Test
    void createFailsAfterRepeatedCollisions() {
        when(repository.findByOwnerAndOriginalUrl(1L, TARGET)).thenReturn(Optional.empty());
        when(repository.saveIfNotExists(any())).thenReturn(false);

        assertThatThrownBy(() -> service.createShortUrl(TARGET, user))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createReturnsExistingMappingForSameUrl() {
        when(repository.findByOwnerAndOriginalUrl(1L, TARGET)).thenReturn(Optional.of(item(CODE, 3)));

        assertThat(service.createShortUrl(TARGET, user).getShortUrl()).isEqualTo(CODE);
        verify(repository, never()).saveIfNotExists(any());
    }

    @Test
    void updateWritesOnlyTheUrlAttributes() {
        when(repository.findByIdAndOwnerId(42L, 1L)).thenReturn(Optional.of(item(CODE, 7)));
        when(repository.updateOriginalUrl(eq(CODE), eq("https://new.example"), eq(UrlMappingItem.ownerUrlKey(1L, "https://new.example")), any())).thenReturn(true);

        UrlMappingDto dto = service.updateUrl(42L, "https://new.example", user);

        assertThat(dto.getOriginalUrl()).isEqualTo("https://new.example");
        assertThat(dto.getClickCount()).isEqualTo(7);
        verify(repository, never()).save(any());
    }

    @Test
    void missingOrForeignMappingIsNotFound() {
        when(repository.findByIdAndOwnerId(42L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteUrl(42L, user))
                .isInstanceOf(UrlMappingNotFoundException.class);
    }

    @Test
    void topUrlsByDateAreOrderedAndCountedForTheRange() {
        when(repository.findByOwnerId(1L)).thenReturn(List.of(item("aaaaaaaa", 100), item("bbbbbbbb", 1)));
        when(analyticsStorage.countPerShortUrlAndDateBetween(any(), any(), any()))
                .thenReturn(Map.of("bbbbbbbb", 5L));

        List<UrlMappingDto> top = service.getTopUrlsByDate(user, 10, LocalDate.now(), LocalDate.now());

        assertThat(top).extracting(UrlMappingDto::getShortUrl).containsExactly("bbbbbbbb", "aaaaaaaa");
        assertThat(top).extracting(UrlMappingDto::getClickCount).containsExactly(5, 0);
    }
}
