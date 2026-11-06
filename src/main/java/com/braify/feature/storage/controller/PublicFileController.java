package com.braify.feature.storage.controller;

import com.braify.feature.storage.model.StoredFile;
import com.braify.feature.storage.service.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * Login-free access to public-purpose files (logos, avatars, template images) — for emails,
 * {@code <img>} tags and the template editor. Redirects to a fresh pre-signed URL, so the bucket
 * stays private and the link never expires. Non-public files answer 404, as if they didn't exist.
 */
@RestController
@RequestMapping("/api/public/files")
@RequiredArgsConstructor
@Tag(name = "Public files", description = "Redirects to public assets (logos, avatars, template images)")
public class PublicFileController {

    private final StorageService storageService;

    @GetMapping("/{fileId}")
    @Operation(summary = "Open a public file", description = "302 to a short-lived pre-signed URL.")
    public ResponseEntity<Void> open(@PathVariable String fileId) {
        StoredFile f;
        try {
            f = storageService.get(fileId);
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
        if (!f.getPurpose().publicRead()) return ResponseEntity.notFound().build();

        StorageService.PresignedUrl u = storageService.presignedUrl(f);
        // Let browsers reuse the redirect for a while, but well inside the URL's lifetime.
        long cacheSeconds = Math.max(0, Math.min(u.expiresInSeconds() / 2, 600));
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(u.url()))
                .cacheControl(CacheControl.maxAge(cacheSeconds, TimeUnit.SECONDS).cachePrivate())
                .build();
    }
}
