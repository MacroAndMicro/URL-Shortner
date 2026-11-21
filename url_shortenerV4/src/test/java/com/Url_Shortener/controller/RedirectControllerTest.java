package com.Url_Shortener.controller;

import com.Url_Shortener.entity.UrlMapping;
import com.Url_Shortener.service.UrlMappingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    void redirectsFromTheShortPath() throws Exception {
        UrlMapping mapping = new UrlMapping();
        mapping.setOriginalUrl("https://example.com");
        when(service.getOriginalUrl(eq("QN7XOa0a"), any(), any(), any())).thenReturn(mapping);

        mockMvc.perform(get("/QN7XOa0a"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com"));
    }

    @Test
    void unknownCodeIsNotFound() throws Exception {
        mockMvc.perform(get("/AAAAAAAA")).andExpect(status().isNotFound());
    }

    @Test
    void pathsThatAreNotCodesDoNotReachTheHandler() throws Exception {
        mockMvc.perform(get("/short")).andExpect(status().isNotFound());
        mockMvc.perform(get("/has-dash")).andExpect(status().isNotFound());
    }

    @Test
    void securityPatternMatchesTheSamePaths() {
        var matcher = PathPatternRequestMatcher.withDefaults().matcher("/{shortUrl:[A-Za-z0-9]{8}}");

        assertThat(matcher.matches(requestTo("/QN7XOa0a"))).isTrue();
        assertThat(matcher.matches(requestTo("/short"))).isFalse();
        assertThat(matcher.matches(requestTo("/has-dash"))).isFalse();
        assertThat(matcher.matches(requestTo("/api/v1/urls"))).isFalse();
    }

    private MockHttpServletRequest requestTo(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        return request;
    }
}
