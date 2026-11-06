package com.braify.security;

import com.braify.feature.session.model.UserSession;
import com.braify.feature.user.model.AppUser;
import com.braify.shared.TtlCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Short-TTL caches for the per-request JWT authentication lookups.
 *
 * <ul>
 *   <li>{@link #sessions}: active {@link UserSession} by jti ({@code findByJtiAndActiveTrue}).</li>
 *   <li>{@link #users}: {@link AppUser} by email ({@code findByEmail}) for {@link UserDetailsServiceImpl#loadUserCached}.</li>
 * </ul>
 *
 * Revocation stays immediate: {@link com.braify.config.AuthCacheEvictionListener} evicts on
 * EVERY save/delete of a {@code UserSession} or {@code AppUser} that goes through Spring Data
 * (repositories and {@code MongoTemplate.save/insert/remove}), which is how every logout,
 * revoke, password/MFA change, user deactivate/delete and role change is persisted. The TTL is
 * only a safety net for writes that bypass mapping events ({@code updateFirst/updateMulti}).
 */
@Slf4j
@Component
public class AuthCache {

    public static final Duration TTL = Duration.ofSeconds(30);

    final TtlCache<String, UserSession> sessions = new TtlCache<>(TTL, 50_000);
    final TtlCache<String, AppUser>     users    = new TtlCache<>(TTL, 50_000);

    public void evictSession(String jti) {
        if (jti == null) sessions.clear();
        else sessions.invalidate(jti);
    }

    /**
     * Evicts a saved session row: its current jti AND any entry cached under an older jti of
     * the same row. Refresh rotates the jti on the same document, so evicting only the new
     * jti would leave the previous access token authenticating until the TTL expires.
     */
    public void evictSessionRow(String sessionId, String jti) {
        if (sessionId == null) { evictSession(jti); return; }
        sessions.invalidateIf((k, s) -> k.equals(jti) || sessionId.equals(s.getId()));
    }

    /** Evicts every cached session belonging to the user (or all sessions when userId is null). */
    public void evictSessionsOfUser(String userId) {
        if (userId == null) sessions.clear();
        else sessions.invalidateIf((jti, s) -> userId.equals(s.getUserId()));
    }

    public void evictAllSessions() {
        sessions.clear();
    }

    /** Evicts the user by id AND by email (covers an email change, where the old key differs). */
    public void evictUser(String userId, String email) {
        if (userId == null && email == null) { users.clear(); return; }
        users.invalidateIf((e, u) -> (email != null && email.equals(e))
                || (userId != null && userId.equals(u.getId())));
    }

    public void evictAllUsers() {
        users.clear();
    }
}
