package com.braify.feature.shortlink.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A short code for an in-app path (e.g. an SMS signing link {@code /s/Ab3…} → {@code /sign/<jwt>}).
 * The code is as sensitive as the path it points to, so it's long and random, and expires with it.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@Document(collection = "short_links")
public class ShortLink {
    @Id private String code;
    /** App-relative path starting with "/" — never an absolute URL (no open redirect). */
    private String path;
    private String orgId;
    @Indexed(expireAfterSeconds = 0)
    private Instant expiresAt;
    private Instant createdAt;
}
