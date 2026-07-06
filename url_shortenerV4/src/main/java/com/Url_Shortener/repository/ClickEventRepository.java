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
    List<ClickEvent> findByUrlMappingAndClickDateBetween(UrlMapping mapping, LocalDateTime startDate, LocalDateTime endDate);
    long countByUrlMappingAndClickDateBetween(UrlMapping mapping, LocalDateTime startDate, LocalDateTime endDate);
    List<ClickEvent> findByUrlMappingInAndClickDateBetween(List<UrlMapping> urlMappings, LocalDateTime startDate, LocalDateTime endDate);
    long countByUrlMappingInAndClickDateBetween(List<UrlMapping> urlMappings, LocalDateTime startDate, LocalDateTime endDate);
    void deleteByUrlMapping(UrlMapping urlMapping);
    long countByUrlMapping(UrlMapping urlMapping);
   @Transactional
   @Modifying
    @Query(value = """
            UPDATE url_mapping
            SET click_count = click_count + 1
            WHERE id = :id""", nativeQuery = true)
    void clickCount(@Param("id") Long id);
}
