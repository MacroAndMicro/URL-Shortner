package com.Url_Shortener.repository;

import com.Url_Shortener.model.ClickEventDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ============================================================
 * MONGODB MIGRATION: Click Event Mongo Repository
 * ============================================================
 *
 * Replaces the JPA ClickEventRepository for analytics persistence.
 * Uses Spring Data MongoDB derived query methods.
 *
 * All methods mirror the original ClickEventRepository signatures
 * but operate on ClickEventDocument in MongoDB instead of the
 * JPA ClickEvent entity in PostgreSQL.
 */
@Repository
public interface ClickEventMongoRepository extends MongoRepository<ClickEventDocument, String> {

    List<ClickEventDocument> findByShortUrlAndClickDateBetween(
            String shortUrl, LocalDateTime startDate, LocalDateTime endDate);

    long countByShortUrlAndClickDateBetween(
            String shortUrl, LocalDateTime startDate, LocalDateTime endDate);

    List<ClickEventDocument> findByShortUrlInAndClickDateBetween(
            List<String> shortUrls, LocalDateTime startDate, LocalDateTime endDate);

    long countByShortUrlInAndClickDateBetween(
            List<String> shortUrls, LocalDateTime startDate, LocalDateTime endDate);

    void deleteByShortUrl(String shortUrl);

    long countByShortUrl(String shortUrl);
}
