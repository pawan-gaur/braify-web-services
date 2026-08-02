package com.braify.feature.bulkemail.controller;

import com.braify.feature.bulkemail.service.EmailTrackingService;
import com.braify.feature.bulkemail.service.TrackingEventService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Public (unauthenticated) endpoints that email clients hit — the open pixel, the click
 * redirect, and the one-click unsubscribe. Permitted in {@code SecurityConfig}.
 *
 * <p>Hidden from Swagger: these are consumed by mail clients, never by the SPA, and must
 * stay fast and side-effect-tolerant (a failed record must never break the redirect/pixel).
 */
@Slf4j
@Hidden
@RestController
@RequestMapping("/api/track")
@RequiredArgsConstructor
public class TrackingController {

    private final EmailTrackingService trackingService;
    private final TrackingEventService eventService;

    /** 1×1 transparent GIF. */
    private static final byte[] PIXEL = Base64.getDecoder().decode(
            "R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");

    // ── Open pixel ──────────────────────────────────────────────────────────────
    @GetMapping("/o/{token}")
    public ResponseEntity<byte[]> open(@PathVariable String token, HttpServletRequest req) {
        try {
            eventService.recordOpen(token, clientIp(req), req.getHeader(HttpHeaders.USER_AGENT));
        } catch (Exception e) {
            log.warn("open-track failed for {}: {}", token, e.getMessage());
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_GIF)
                .cacheControl(CacheControl.noCache().noTransform().mustRevalidate())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(PIXEL);
    }

    // ── Click redirect ──────────────────────────────────────────────────────────
    @GetMapping("/c/{token}")
    public ResponseEntity<Void> click(@PathVariable String token,
                                      @RequestParam("u") String u,
                                      @RequestParam("s") String s,
                                      HttpServletRequest req) {
        String url = trackingService.verifyClick(token, u, s);
        if (url == null) {
            // Signature/paylod invalid — refuse to act as an open redirect.
            return ResponseEntity.badRequest().build();
        }
        try {
            eventService.recordClick(token, url, clientIp(req), req.getHeader(HttpHeaders.USER_AGENT));
        } catch (Exception e) {
            log.warn("click-track failed for {}: {}", token, e.getMessage());
        }
        try {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
        } catch (IllegalArgumentException badUri) {
            return ResponseEntity.badRequest().build();
        }
    }

    // ── One-click unsubscribe ─────────────────────────────────────────────────────
    @GetMapping("/u/{token}")
    public ResponseEntity<String> unsubscribe(@PathVariable String token,
                                              @RequestParam("s") String s,
                                              HttpServletRequest req) {
        if (!trackingService.verifyUnsubscribe(token, s)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.TEXT_HTML)
                    .body(page("Invalid link", "This unsubscribe link is invalid or has expired."));
        }
        String email;
        try {
            email = eventService.recordUnsubscribe(token, clientIp(req), req.getHeader(HttpHeaders.USER_AGENT));
        } catch (Exception e) {
            log.warn("unsubscribe failed for {}: {}", token, e.getMessage());
            email = null;
        }
        String who = (email != null && !email.isBlank()) ? escape(email) : "This address";
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(page("You're unsubscribed",
                        who + " has been removed and will no longer receive these emails."));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String page(String title, String body) {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + title + "</title></head>"
                + "<body style=\"font-family:Arial,Helvetica,sans-serif;background:#f9fafb;margin:0;"
                + "display:flex;min-height:100vh;align-items:center;justify-content:center\">"
                + "<div style=\"background:#fff;border:1px solid #e5e7eb;border-radius:16px;padding:40px 32px;"
                + "max-width:420px;text-align:center;box-shadow:0 1px 3px rgba(0,0,0,.06)\">"
                + "<h1 style=\"font-size:20px;color:#111827;margin:0 0 12px\">" + title + "</h1>"
                + "<p style=\"font-size:14px;color:#6b7280;line-height:1.6;margin:0\">" + body + "</p>"
                + "</div></body></html>";
    }
}
