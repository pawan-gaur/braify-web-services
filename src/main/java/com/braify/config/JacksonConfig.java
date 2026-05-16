package com.braify.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.format.DateTimeFormatter;

/**
 * Jackson configuration that serializes all {@link java.time.LocalDateTime} values
 * as ISO-8601 strings with a trailing 'Z' (e.g. "2026-05-16T09:51:00Z").
 *
 * Why this matters:
 *   The JVM is forced to UTC in {@code PdfGeneratorApplication.main()} via
 *   {@code TimeZone.setDefault(UTC)}, so every {@code LocalDateTime.now()} call
 *   stores a UTC value.  This serializer then appends 'Z' to make that contract
 *   explicit to JavaScript clients, which treat the 'Z' as "parse as UTC, then
 *   convert to the browser's local timezone" — giving correct local display times.
 *
 * Without the 'Z':
 *   {@code new Date("2026-05-16T09:51:00")} is treated as LOCAL time by browsers,
 *   so a UTC+5:30 user sees 09:51 AM instead of the correct 03:21 PM.
 */
@Configuration
public class JacksonConfig {

    /** ISO-8601 format with explicit UTC marker. */
    private static final DateTimeFormatter UTC_ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer localDateTimeAsUtc() {
        return builder -> {
            // Register the JSR-310 module so Spring uses our custom serializer
            // rather than its default timestamp-as-array behaviour.
            builder.modules(new JavaTimeModule());
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            // Override LocalDateTime serialization to always emit 'Z' suffix.
            builder.serializerByType(
                    java.time.LocalDateTime.class,
                    new LocalDateTimeSerializer(UTC_ISO)
            );
        };
    }
}
