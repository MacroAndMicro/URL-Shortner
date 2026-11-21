package com.Url_Shortener.controller;

import com.Url_Shortener.service.RedirectTarget;
import com.Url_Shortener.service.UrlMappingService;
import com.Url_Shortener.shortcode.ShortCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RedirectControllerTest {

    private UrlMappingService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(UrlMappingService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RedirectController(service)).build();
    }

    @Test
    void paidLinksRedirectWith302ThatIsNeverCached() throws Exception {
        when(service.resolve(eq("QN7XOa0a"), any(), any(), any()))
                .thenReturn(Optional.of(new RedirectTarget("https://example.com", false, null)));

        mockMvc.perform(get("/QN7XOa0a"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void guestAndFreeLinksRedirectWith301CachedUntilExpiry() throws Exception {
        when(service.resolve(eq("QN7XOa0a"), any(), any(), any()))
                .thenReturn(Optional.of(new RedirectTarget("https://example.com", true, 3600L)));

        mockMvc.perform(get("/QN7XOa0a"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "https://example.com"))
                .andExpect(header().string("Cache-Control", "max-age=3600, public"));
    }

    @Test
    void unknownOrExpiredCodeIsNotFound() throws Exception {
        mockMvc.perform(get("/AAAAAAAA")).andExpect(status().isNotFound());
    }

    @Test
    void redirectsSnowflakeAndCustomStyleCodes() throws Exception {
        when(service.resolve(any(), any(), any(), any()))
                .thenReturn(Optional.of(new RedirectTarget("https://example.com", false, null)));

        for (String code : new String[]{"1b2C3d4E5f", "1b2C3d4E5fG", "my-link", "my_link"}) {
            mockMvc.perform(get("/" + code)).andExpect(status().isFound());
        }
    }

    @Test
    void pathsThatAreNotCodesDoNotReachTheHandler() throws Exception {
        mockMvc.perform(get("/ab")).andExpect(status().isNotFound());
        mockMvc.perform(get("/" + "a".repeat(33))).andExpect(status().isNotFound());
        mockMvc.perform(get("/favicon.ico")).andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }

    @Test
    void securityPatternMatchesTheSamePaths() {
        var matcher = PathPatternRequestMatcher.withDefaults().matcher(ShortCode.PATH_PATTERN);

        assertThat(matcher.matches(requestTo("/QN7XOa0a"))).isTrue();
        assertThat(matcher.matches(requestTo("/1b2C3d4E5fG"))).isTrue();
        assertThat(matcher.matches(requestTo("/has-dash"))).isTrue();
        assertThat(matcher.matches(requestTo("/ab"))).isFalse();
        assertThat(matcher.matches(requestTo("/favicon.ico"))).isFalse();
        assertThat(matcher.matches(requestTo("/api/v1/urls"))).isFalse();
    }

    @Test
    void rateLimiterRecognisesTheSamePaths() {
        assertThat(ShortCode.isShortCodePath("/1b2C3d4E5fG")).isTrue();
        assertThat(ShortCode.isShortCodePath("/my-link")).isTrue();
        assertThat(ShortCode.isShortCodePath("/ab")).isFalse();
        assertThat(ShortCode.isShortCodePath("/api/v1/urls")).isFalse();
    }

    private MockHttpServletRequest requestTo(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }
}
