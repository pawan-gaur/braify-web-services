package com.braify.controller;

import com.braify.dto.SessionResponse;
import com.braify.model.AppUser;
import com.braify.security.JwtUtil;
import com.braify.security.UserDetailsImpl;
import com.braify.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;
    private final JwtUtil        jwtUtil;

    private AppUser currentUser(Authentication auth) {
        return ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
    }

    /** Extract the JTI of the caller's current token from the Authorization header. */
    private String currentJti(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                return jwtUtil.extractJti(header.substring(7));
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ── GET /api/sessions ─────────────────────────────────────────────────────

    /**
     * Lists active sessions scoped by the caller's role:
     * PLATFORM_ADMIN → all | ORG_ADMIN → their org | ADMIN → ADMIN+USER in org | USER → own only.
     */
    @GetMapping
    public List<SessionResponse> list(Authentication auth, HttpServletRequest request) {
        return sessionService.listSessions(currentUser(auth), currentJti(request));
    }

    // ── DELETE /api/sessions/{id} ─────────────────────────────────────────────

    /**
     * Revokes a specific session. The caller must have authority over the
     * target session (same RBAC rules as listing).
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable String id,
                                       Authentication auth,
                                       HttpServletRequest request) {
        sessionService.revokeSession(id, currentUser(auth), currentJti(request));
        return ResponseEntity.noContent().build();
    }

    // ── DELETE /api/sessions/me/others ────────────────────────────────────────

    /**
     * Revokes all of the caller's active sessions except the current one.
     * Useful for "sign out everywhere else" functionality.
     */
    @DeleteMapping("/me/others")
    public ResponseEntity<Map<String, Integer>> revokeOthers(Authentication auth,
                                                              HttpServletRequest request) {
        int count = sessionService.revokeAllMyOtherSessions(currentUser(auth), currentJti(request));
        return ResponseEntity.ok(Map.of("revoked", count));
    }
}
