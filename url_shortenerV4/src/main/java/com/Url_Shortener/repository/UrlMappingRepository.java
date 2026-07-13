package com.Url_Shortener.repository;

import com.Url_Shortener.entity.UrlMapping;
import com.Url_Shortener.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

// ============================================================
// OLD POSTGRES IMPLEMENTATION — Commented out for DynamoDB migration
// ============================================================
// This JPA repository has been replaced by UrlMappingDynamoRepository.
// Kept for reference during the polyglot persistence migration.
// ============================================================

//@Repository
//public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {
//    UrlMapping findByShortUrl(String shortUrl);
//    UrlMapping findByShortUrlAndUser(String shortUrl, User user);
//    List<UrlMapping> findByUser(User user);
//    Page<UrlMapping> findByUser(User user, Pageable pageable);
//    Optional<UrlMapping> findByIdAndUser(Long id, User user);
//    boolean existsByShortUrl(String shortUrl);
//    boolean existsByOriginalUrlAndUser(String originalUrl, User user);
//}
