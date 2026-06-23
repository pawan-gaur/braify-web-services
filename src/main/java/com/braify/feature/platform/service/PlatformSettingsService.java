package com.braify.feature.platform.service;

import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.platform.model.PlatformSettings;
import com.braify.feature.platform.model.PlatformSettings.Access;
import com.braify.feature.platform.model.PlatformSettings.Security;
import com.braify.feature.platform.repository.PlatformSettingsRepository;
import com.braify.feature.user.model.AppUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Reads and writes the single platform-wide settings document.
 * Only PLATFORM_ADMIN may call the mutating method (enforced at the controller
 * via {@code @PreAuthorize}); every change is written to the audit log.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformSettingsService {

    private final PlatformSettingsRepository repo;
    private final AuditLogService            auditLogService;

    /**
     * In-memory cache of the singleton settings. Enforcement runs on hot paths
     * (e.g. JwtAuthFilter on every request), so we avoid a Mongo read each time.
     * Refreshed on every {@link #updateSettings}.
     * Note: in a multi-instance deployment each node caches independently — a change
     * on one node is picked up by others on restart (acceptable for these settings).
     */
    private volatile PlatformSettings cached;

    /** Returns the platform settings, lazily creating the default document on first access. */
    public PlatformSettings getSettings() {
        PlatformSettings c = cached;
        if (c != null) return c;
        PlatformSettings loaded = repo.findById(PlatformSettings.SINGLETON_ID)
                .orElseGet(() -> repo.save(
                        PlatformSettings.builder().id(PlatformSettings.SINGLETON_ID).build()));
        cached = loaded;
        return loaded;
    }

    /**
     * Applies an incoming settings payload (from the PLATFORM_ADMIN UI), after
     * server-side sanitisation, and records an audit entry describing what changed.
     */
    public PlatformSettings updateSettings(PlatformSettings incoming, AppUser caller) {
        PlatformSettings current = getSettings();

        Security security = incoming.getSecurity() != null ? incoming.getSecurity() : current.getSecurity();
        Access   access   = incoming.getAccess()   != null ? incoming.getAccess()   : current.getAccess();
        sanitize(security, access);

        PlatformSettings toSave = PlatformSettings.builder()
                .id(PlatformSettings.SINGLETON_ID)
                .security(security)
                .access(access)
                .updatedBy(caller != null ? caller.getEmail() : "system")
                .build();

        PlatformSettings saved = repo.save(toSave);
        cached = saved;   // refresh the hot-path cache

        Map<String, Object> changes = diff(current, saved);
        if (caller != null) {
            auditLogService.logByUser(
                    PlatformSettings.SINGLETON_ID, "Platform security & access policies",
                    AuditLog.Action.PLATFORM_SETTINGS_UPDATED, AuditLog.ResourceType.PLATFORM,
                    0, changes.isEmpty() ? null : changes, caller);
        }
        log.info("Platform settings updated by '{}' — {} group(s) changed",
                caller != null ? caller.getEmail() : "system", changes.size());
        return saved;
    }

    // ── Sanitisation — keep stored values within safe bounds ──────────────────

    private void sanitize(Security s, Access a) {
        if (s != null) {
            var m = s.getMfa();
            // When MFA is required, at least one method must remain enabled.
            if (m != null && m.isRequired() && !m.isTotp() && !m.isEmailOtp()) {
                m.setTotp(true);
            }
            var p = s.getPassword();
            if (p != null) {
                p.setMinLength(clamp(p.getMinLength(), 8, 64));
                if (p.getExpiryDays() < 0) p.setExpiryDays(0);
                p.setReuseCount(clamp(p.getReuseCount(), 1, 24));
            }
            var l = s.getLockout();
            if (l != null) {
                l.setMaxFailedAttempts(clamp(l.getMaxFailedAttempts(), 1, 20));
                l.setLockoutMinutes(clamp(l.getLockoutMinutes(), 1, 1440));
            }
            var se = s.getSessions();
            if (se != null) {
                se.setSessionTimeoutHours(clamp(se.getSessionTimeoutHours(), 1, 168));
                se.setIdleTimeoutMinutes(clamp(se.getIdleTimeoutMinutes(), 1, 1440));
                se.setMaxConcurrent(clamp(se.getMaxConcurrent(), 1, 20));
            }
        }
        if (a != null) {
            String role = a.getDefaultRole();
            if (!"USER".equals(role) && !"ADMIN".equals(role)) {
                a.setDefaultRole("USER"); // never default new users to an elevated role
            }
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ── Audit diff — record which groups changed (value-level equals) ─────────

    private Map<String, Object> diff(PlatformSettings before, PlatformSettings after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        Security b = before.getSecurity(), a = after.getSecurity();
        if (b != null && a != null) {
            putIfChanged(changes, "mfa",      b.getMfa(),      a.getMfa());
            putIfChanged(changes, "password", b.getPassword(), a.getPassword());
            putIfChanged(changes, "lockout",  b.getLockout(),  a.getLockout());
            putIfChanged(changes, "sessions", b.getSessions(), a.getSessions());
        }
        putIfChanged(changes, "access", before.getAccess(), after.getAccess());
        return changes;
    }

    private void putIfChanged(Map<String, Object> changes, String key, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            Map<String, Object> fromTo = new LinkedHashMap<>();
            fromTo.put("from", before);
            fromTo.put("to", after);
            changes.put(key, fromTo);
        }
    }
}
