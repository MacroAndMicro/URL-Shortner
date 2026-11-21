package com.Url_Shortener.config;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ZonedDateTimeJsonTest {

    @Test
    void dateTimesCarryTheServerOffsetSoBrowsersReadTheRightInstant() {
        JsonMapper mapper = JsonMapper.builder()
                .addModule(new SimpleModule().addSerializer(LocalDateTime.class, new ZonedDateTimeJson.Serializer()))
                .build();
        LocalDateTime local = LocalDateTime.of(2026, 11, 21, 7, 11, 19);

        String json = mapper.writeValueAsString(Map.of("at", local));
        String written = json.substring(json.indexOf(":\"") + 2, json.lastIndexOf('"'));

        assertThat(written).matches("2026-11-21T07:11:19(Z|[+-]\\d\\d:\\d\\d)");
        assertThat(OffsetDateTime.parse(written).toInstant())
                .isEqualTo(local.atZone(ZoneId.systemDefault()).toInstant());
    }
}
