package com.Url_Shortener.repository;

import com.Url_Shortener.entity.ClickEvent;
import com.Url_Shortener.entity.UrlMapping;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ClickEventRepository extends JpaRepository<ClickEvent, Long>{

    // ============================================================
    // POLYGLOT PERSISTENCE MIGRATION:
    // Method signatures changed from UrlMapping entity references
    // to String shortUrl because URL mappings are now in DynamoDB.
    // Click events remain in PostgreSQL (analytics migration to MongoDB
    // will happen in a future phase).
    // ============================================================

    // OLD POSTGRES: findByUrlMappingAndClickDateBetween(UrlMapping mapping, ...)
    // NEW DYNAMODB:  findByShortUrlAndClickDateBetween(String shortUrl, ...)
    List<ClickEvent> findByShortUrlAndClickDateBetween(String shortUrl, LocalDateTime startDate, LocalDateTime endDate);
    long countByShortUrlAndClickDateBetween(String shortUrl, LocalDateTime startDate, LocalDateTime endDate);

    // OLD POSTGRES: findByUrlMappingInAndClickDateBetween(List<UrlMapping>, ...)
    // NEW DYNAMODB:  findByShortUrlInAndClickDateBetween(List<String>, ...)
    List<ClickEvent> findByShortUrlInAndClickDateBetween(List<String> shortUrls, LocalDateTime startDate, LocalDateTime endDate);
    long countByShortUrlInAndClickDateBetween(List<String> shortUrls, LocalDateTime startDate, LocalDateTime endDate);

    // OLD POSTGRES: deleteByUrlMapping(UrlMapping urlMapping)
    // NEW DYNAMODB:  deleteByShortUrl(String shortUrl)
    void deleteByShortUrl(String shortUrl);
    long countByShortUrl(String shortUrl);

    // ============================================================
    // OLD POSTGRES IMPLEMENTATION — Commented out
    // ============================================================
    // This native query updated the url_mapping table in PostgreSQL.
    // Click count increment is now handled by DynamoDB:
    //   UrlMappingDynamoRepository.incrementClickCount(shortUrl)
    // ============================================================
    //@Transactional
    //@Modifying
    //@Query(value = """
    //        UPDATE url_mapping
    //        SET click_count = click_count + 1
    //        WHERE id = :id""", nativeQuery = true)
    //void clickCount(@Param("id") Long id);
}
