package com.Url_Shortener.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class UrlMappingDtoJsonTest {

    @Test
    void idIsSerializedAsAStringSoJavaScriptKeepsEveryDigit() {
        UrlMappingDto dto = new UrlMappingDto();
        dto.setId(Long.MAX_VALUE);

        String json = JsonMapper.builder().build().writeValueAsString(dto);

        assertThat(json).contains("\"id\":\"9223372036854775807\"");
    }
}
