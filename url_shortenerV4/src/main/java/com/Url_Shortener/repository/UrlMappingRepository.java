package com.Url_Shortener.repository;

import com.Url_Shortener.entity.UrlMapping;
import com.Url_Shortener.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {
    UrlMapping findByShortUrl(String shortUrl);
    List<UrlMapping> findByUser(User user);

    boolean existsByShortUrl(StringBuilder shortUrl);
}
