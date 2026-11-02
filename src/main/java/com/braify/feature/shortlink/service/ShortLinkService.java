package com.braify.feature.shortlink.service;

import com.braify.feature.shortlink.model.ShortLink;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;

/** Short, unguessable codes for long in-app links (used in SMS, where every character counts). */
@Service
@RequiredArgsConstructor
public class ShortLinkService {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    /** 10 chars of a 57-symbol alphabet ≈ 58 bits — not guessable, even with no rate limit. */
    private static final int LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MongoTemplate mongoTemplate;

    /** @param path app-relative path ("/sign/…"); expires at {@code expiresAt} (TTL-deleted). */
    public String create(String path, String orgId, Instant expiresAt) {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) {
            throw new IllegalArgumentException("Short links point to app paths only");
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = randomCode();
            if (mongoTemplate.findById(code, ShortLink.class) != null) continue;
            mongoTemplate.insert(ShortLink.builder().code(code).path(path).orgId(orgId)
                    .expiresAt(expiresAt).createdAt(Instant.now()).build());
            return code;
        }
        throw new IllegalStateException("Could not allocate a short link");
    }

    public Optional<String> resolve(String code) {
        if (code == null || code.length() != LENGTH) return Optional.empty();
        ShortLink link = mongoTemplate.findById(code, ShortLink.class);
        if (link == null || (link.getExpiresAt() != null && link.getExpiresAt().isBefore(Instant.now()))) {
            return Optional.empty();
        }
        return Optional.of(link.getPath());
    }

    private static String randomCode() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
