package com.Url_Shortener.config;

import org.springframework.boot.jackson.JacksonComponent;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Writes every LocalDateTime in API responses with the server's UTC offset,
 * e.g. "2026-11-21T07:11:19Z" or "2026-11-21T12:41:19+05:30".
 *
 * The application keeps times as LocalDateTime in the server's time zone.
 * Without an offset, browsers read them as their own local time, so a
 * visitor in another zone saw expiry and edit deadlines hours off.
 */
@JacksonComponent
public class ZonedDateTimeJson {

    public static class Serializer extends ValueSerializer<LocalDateTime> {

        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializationContext ctxt) {
            gen.writeString(value.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString());
        }
    }
}
