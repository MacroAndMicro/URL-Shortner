package com.Url_Shortener.consumer;

import com.Url_Shortener.event.ClickEventCreatedEvent;
import com.Url_Shortener.metrics.UrlShortenerMetrics;
import com.Url_Shortener.repository.UrlMappingDynamoRepository;
import com.Url_Shortener.storage.AnalyticsStorage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class KafkaAnalyticsConsumerTest {

    private AnalyticsStorage storage;
    private UrlMappingDynamoRepository repository;
    private Acknowledgment ack;
    private KafkaAnalyticsConsumer consumer;
    private ClickEventCreatedEvent event;

    @BeforeEach
    void setUp() {
        storage = mock(AnalyticsStorage.class);
        repository = mock(UrlMappingDynamoRepository.class);
        ack = mock(Acknowledgment.class);
        consumer = new KafkaAnalyticsConsumer(storage, repository, new UrlShortenerMetrics(new SimpleMeterRegistry()));
        event = new ClickEventCreatedEvent("QN7XOa0a", null, "hash", "ref", "agent", null);
    }

    private void stubSave(boolean stored) {
        when(storage.saveClickEvent(any(), any(), any(), any(), any(), any())).thenReturn(stored);
    }

    @Test
    void newEventIsStoredCountedAndAcknowledged() {
        stubSave(true);

        consumer.consume(event, ack);

        verify(repository).incrementClickCount("QN7XOa0a");
        verify(ack).acknowledge();
    }

    @Test
    void clickDateComesFromTheEventNotProcessingTime() {
        stubSave(true);
        Instant clickedAt = Instant.parse("2026-01-15T10:00:00Z");
        event.setTimestamp(clickedAt);

        consumer.consume(event, ack);

        verify(storage).saveClickEvent(eq(event.getEventId()), eq("QN7XOa0a"),
                eq(LocalDateTime.ofInstant(clickedAt, ZoneId.systemDefault())),
                eq("hash"), eq("ref"), eq("agent"));
    }

    @Test
    void redeliveredEventIsNotCountedTwice() {
        stubSave(false);

        consumer.consume(event, ack);

        verify(repository, never()).incrementClickCount(any());
        verify(ack).acknowledge();
    }

    @Test
    void storageFailurePropagatesWithoutAcknowledging() {
        when(storage.saveClickEvent(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("mongo down"));

        assertThatThrownBy(() -> consumer.consume(event, ack)).isInstanceOf(IllegalStateException.class);

        verify(ack, never()).acknowledge();
    }
}
