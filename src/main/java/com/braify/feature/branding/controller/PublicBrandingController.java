package com.braify.feature.branding.controller;

import com.braify.feature.branding.service.OrgBrandingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Public (unauthenticated) endpoint that streams an organisation's branding logo.
 *
 * <p>This gives the logo a stable https URL that renders in emails (which block {@code data:}
 * URIs and can't use pre-signed/expiring links). The bytes are read from the org's cloud bucket
 * (or decoded from an inline data URL for orgs without cloud storage).
 */
@Slf4j
@Tag(name = "Public Branding", description = "Unauthenticated logo streaming for use in emails.")
@RestController
@RequestMapping("/api/public/branding")
@RequiredArgsConstructor
public class PublicBrandingController {

    private final OrgBrandingService brandingService;

    @Operation(summary = "Get an organisation's logo image")
    @GetMapping("/{orgId}/logo")
    public ResponseEntity<byte[]> logo(@PathVariable String orgId) {
        try {
            OrgBrandingService.LogoData data = brandingService.getLogoData(orgId);
            if (data == null || data.bytes() == null || data.bytes().length == 0) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(
                            data.contentType() != null ? data.contentType() : "image/png"))
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic())
                    .body(data.bytes());
        } catch (Exception e) {
            log.warn("Failed to serve logo for org {}: {}", orgId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }
}
