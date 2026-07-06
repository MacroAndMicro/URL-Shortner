package com.Url_Shortener.service;

import com.Url_Shortener.dto.ClickEventDetailDto;
import com.Url_Shortener.dto.ClickEventDto;
import com.Url_Shortener.dto.DashboardDto;
import com.Url_Shortener.dto.UrlMappingDto;
import com.Url_Shortener.entity.ClickEvent;
import com.Url_Shortener.entity.UrlMapping;
import com.Url_Shortener.entity.User;
import com.Url_Shortener.repository.ClickEventRepository;
import com.Url_Shortener.repository.UrlMappingRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UrlMappingService {
    private final UrlMappingRepository urlMappingRepository;
    private final ClickEventRepository clickEventRepository;

    public UrlMappingService(UrlMappingRepository urlMappingRepository, ClickEventRepository clickEventRepository) {
        this.urlMappingRepository = urlMappingRepository;
        this.clickEventRepository = clickEventRepository;
    }

    public UrlMappingDto createShortUrl(String originalUrl, User user) {
        // Return existing mapping if user already shortened this URL
        if (urlMappingRepository.existsByOriginalUrlAndUser(originalUrl, user)) {
            UrlMapping existing = urlMappingRepository.findByUser(user).stream()
                    .filter(m -> m.getOriginalUrl().equals(originalUrl))
                    .findFirst()
                    .orElseThrow();
            return convertToDto(existing);
        }
        String shortUrl = generateShortUrl();
        UrlMapping urlMapping = new UrlMapping();
        urlMapping.setOriginalUrl(originalUrl);
        urlMapping.setShortUrl(shortUrl);
        urlMapping.setUser(user);
        urlMapping.setCreatedDate(LocalDateTime.now());
        UrlMapping savedUrlMapping = urlMappingRepository.save(urlMapping);
        return convertToDto(savedUrlMapping);
    }

    private UrlMappingDto convertToDto(UrlMapping urlMapping){
        UrlMappingDto urlMappingDto = new UrlMappingDto();
        urlMappingDto.setId(urlMapping.getId());
        urlMappingDto.setOriginalUrl(urlMapping.getOriginalUrl());
        urlMappingDto.setShortUrl(urlMapping.getShortUrl());
        urlMappingDto.setClickCount(urlMapping.getClickCount());
        urlMappingDto.setCreatedDate(urlMapping.getCreatedDate());
        urlMappingDto.setUsername(urlMapping.getUser().getUsername());
        return urlMappingDto;
    }

    private String generateShortUrl() {

        String characters =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

        SecureRandom random = new SecureRandom();

        String shortUrl;

        do {
            StringBuilder sb = new StringBuilder(8);

            for (int i = 0; i < 8; i++) {
                sb.append(
                        characters.charAt(
                                random.nextInt(characters.length())
                        )
                );
            }

            shortUrl = sb.toString();

        } while (urlMappingRepository.existsByShortUrl(shortUrl));

        return shortUrl;
    }

    public List<UrlMappingDto> getUrlsByUser(User user) {
        return urlMappingRepository.findByUser(user).stream()
                .map(this::convertToDto)
                .toList();
    }

    public Page<UrlMappingDto> getUrlsByUser(User user, Pageable pageable) {
        return urlMappingRepository.findByUser(user, pageable)
                .map(this::convertToDto);
    }

    public List<ClickEventDto> getClickEventsByDate(String shortUrl, LocalDateTime start, LocalDateTime end, User user) {
        UrlMapping urlMapping = urlMappingRepository.findByShortUrlAndUser(shortUrl, user);
        if (urlMapping != null) {
            return clickEventRepository.findByUrlMappingAndClickDateBetween(urlMapping, start, end).stream()
                    .collect(Collectors.groupingBy(click -> click.getClickDate().toLocalDate(), Collectors.counting()))
                    .entrySet().stream()
                    .map(entry -> {
                        ClickEventDto clickEventDTO = new ClickEventDto();
                        clickEventDTO.setClickDate(entry.getKey());
                        clickEventDTO.setCount(entry.getValue());
                        return clickEventDTO;
                    })
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    public List<ClickEventDto> getTotalClicksByUserAndDate(User user, LocalDate start, LocalDate end) {
        List<UrlMapping> urlMappings = urlMappingRepository.findByUser(user);
        List<ClickEvent> clickEvents = clickEventRepository.findByUrlMappingInAndClickDateBetween(urlMappings, start.atStartOfDay(), end.plusDays(1).atStartOfDay());
        return clickEvents.stream()
                .collect(Collectors.groupingBy(click -> click.getClickDate().toLocalDate(), Collectors.counting()))
                .entrySet().stream()
                .map(entry -> {
                    ClickEventDto dto = new ClickEventDto();
                    dto.setClickDate(entry.getKey());
                    dto.setCount(entry.getValue());
                    return dto;
                })
                .sorted((a, b) -> a.getClickDate().compareTo(b.getClickDate()))
                .collect(Collectors.toList());
    }

    public UrlMapping getOriginalUrl(String shortUrl, String ipAddress, String referrer, String userAgent) {

        UrlMapping urlMapping =
                urlMappingRepository.findByShortUrl(shortUrl);

        if (urlMapping != null) {

            clickEventRepository.clickCount(urlMapping.getId());

            ClickEvent clickEvent = new ClickEvent();
            clickEvent.setClickDate(LocalDateTime.now());
            clickEvent.setUrlMapping(urlMapping);
            clickEvent.setIpAddress(ipAddress);
            clickEvent.setReferrer(referrer);
            clickEvent.setUserAgent(userAgent);

            clickEventRepository.save(clickEvent);
        }

        return urlMapping;
    }

    public UrlMappingDto getUrlByIdAndUser(Long id, User user) {
        UrlMapping urlMapping = urlMappingRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new IllegalArgumentException("URL mapping not found or access denied"));
        return convertToDto(urlMapping);
    }

    public UrlMappingDto updateUrl(Long id, String newOriginalUrl, User user) {
        UrlMapping urlMapping = urlMappingRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new IllegalArgumentException("URL mapping not found or access denied"));
        if (newOriginalUrl != null && !newOriginalUrl.isBlank()) {
            urlMapping.setOriginalUrl(newOriginalUrl);
        }
        UrlMapping saved = urlMappingRepository.save(urlMapping);
        return convertToDto(saved);
    }

    public void deleteUrl(Long id, User user) {
        UrlMapping urlMapping = urlMappingRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new IllegalArgumentException("URL mapping not found or access denied"));
        clickEventRepository.deleteByUrlMapping(urlMapping);
        urlMappingRepository.delete(urlMapping);
    }

    public List<ClickEventDetailDto> getClickEventDetails(String shortUrl, LocalDateTime start, LocalDateTime end, User user) {
        UrlMapping urlMapping = urlMappingRepository.findByShortUrlAndUser(shortUrl, user);
        if (urlMapping == null) {
            throw new IllegalArgumentException("Short URL not found or access denied");
        }
        return clickEventRepository.findByUrlMappingAndClickDateBetween(urlMapping, start, end).stream()
                .map(click -> new ClickEventDetailDto(
                        click.getId(),
                        click.getClickDate(),
                        click.getIpAddress(),
                        click.getReferrer(),
                        click.getUserAgent()
                ))
                .toList();
    }

    public DashboardDto getDashboard(User user) {
        List<UrlMapping> userUrls = urlMappingRepository.findByUser(user);
        long totalUrls = userUrls.size();
        long totalClicks = userUrls.stream().mapToInt(UrlMapping::getClickCount).sum();

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime todayEnd = todayStart.plusDays(1);
        long clicksToday = clickEventRepository.countByUrlMappingInAndClickDateBetween(
                userUrls, todayStart, todayEnd);

        LocalDateTime weekStart = LocalDate.now().minusDays(7).atStartOfDay();
        long clicksThisWeek = clickEventRepository.countByUrlMappingInAndClickDateBetween(
                userUrls, weekStart, todayEnd);

        List<UrlMappingDto> topUrls = userUrls.stream()
                .sorted((a, b) -> Integer.compare(b.getClickCount(), a.getClickCount()))
                .limit(5)
                .map(this::convertToDto)
                .toList();

        return new DashboardDto(totalUrls, totalClicks, clicksToday, clicksThisWeek, topUrls);
    }

    public List<UrlMappingDto> getTopUrls(User user, int limit) {
        return urlMappingRepository.findByUser(user).stream()
                .sorted((a, b) -> Integer.compare(b.getClickCount(), a.getClickCount()))
                .limit(limit)
                .map(this::convertToDto)
                .toList();
    }

    public List<UrlMappingDto> getTopUrlsByDate(User user, int limit, LocalDate start, LocalDate end) {
        List<UrlMapping> userUrls = urlMappingRepository.findByUser(user);
        return userUrls.stream()
                .sorted((a, b) -> {
                    long clicksA = clickEventRepository.countByUrlMappingAndClickDateBetween(
                            a, start.atStartOfDay(), end.plusDays(1).atStartOfDay());
                    long clicksB = clickEventRepository.countByUrlMappingAndClickDateBetween(
                            b, start.atStartOfDay(), end.plusDays(1).atStartOfDay());
                    return Long.compare(clicksB, clicksA);
                })
                .limit(limit)
                .map(this::convertToDto)
                .toList();
    }
}
