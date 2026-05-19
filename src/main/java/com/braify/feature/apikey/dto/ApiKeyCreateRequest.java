package com.braify.feature.apikey.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import lombok.Data;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * Request body for POST /api/organizations/{orgId}/api-keys
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiKeyCreateRequest {

    /** Human-readable label for this key, e.g. "Production Key" */
    private String name;

    /**
     * Feature keys this API key is permitted to access.
     * Must be a subset of the organisation's own enabled features.
     * Valid values: PDF_TEMPLATES, EMAIL_TEMPLATES, E_SIGN
     */
    private Set<String> allowedFeatures;

    /**
     * Optional expiry.  Accepts either:
     * <ul>
     *   <li>ISO date-time: {@code "2026-05-30T23:59:59"}</li>
     *   <li>ISO date only: {@code "2026-05-30"} — treated as end-of-day (23:59:59)</li>
     * </ul>
     * When null the key never expires.
     */
    @JsonDeserialize(using = FlexibleLocalDateTimeDeserializer.class)
    private LocalDateTime expiresAt;

    // ── Inner deserializer ────────────────────────────────────────────────────

    /**
     * Accepts both "yyyy-MM-dd" (date picker format from browsers) and
     * "yyyy-MM-dd'T'HH:mm:ss" (full LocalDateTime) so the API is lenient
     * regardless of what the frontend sends.
     */
    static class FlexibleLocalDateTimeDeserializer extends StdDeserializer<LocalDateTime> {

        FlexibleLocalDateTimeDeserializer() {
            super(LocalDateTime.class);
        }

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String raw = p.getText();
            if (raw == null || raw.isBlank()) return null;
            raw = raw.trim();

            // Try full LocalDateTime first (e.g. "2026-05-30T23:59:59")
            try {
                return LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException ignored) {
                // fall through
            }

            // Try date-only (e.g. "2026-05-30") — treat as end-of-day
            try {
                return LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE).atTime(23, 59, 59);
            } catch (DateTimeParseException ignored) {
                // fall through
            }

            throw new IOException("Cannot parse date/datetime: '" + raw +
                    "'. Expected 'yyyy-MM-dd' or 'yyyy-MM-ddTHH:mm:ss'.");
        }
    }
}
