package com.braify.security;

import com.braify.config.RequestLoggingFilter;
import com.braify.feature.platform.service.PlatformSettingsService;
import com.braify.feature.session.model.UserSession;
import com.braify.feature.session.repository.UserSessionRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Validates JWT Bearer tokens on every request and populates the SecurityContext.
 *
 * <h3>Performance fixes</h3>
 * <ul>
 *   <li>Single repository call per request — the previous code called
 *       {@code findByJtiAndActiveTrue} <em>twice</em>; we now reuse the result.</li>
 *   <li>The JWT is parsed/verified once per request (was ~6 times) with a cached key.</li>
 *   <li>Session-by-jti and user-by-email lookups go through {@link AuthCache} (short TTL,
 *       evicted on every session/user write).</li>
 *   <li>{@code lastUsedAt} is a throttled single-field {@code $set}, not a full save.</li>
 * </ul>
 *
 * <h3>Observability</h3>
 * Sets the {@link RequestLoggingFilter#CALLER_ATTR} request attribute so
 * {@link RequestLoggingFilter} can log the authenticated user's e-mail even after
 * Spring Security has cleared the SecurityContext.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserDetailsServiceImpl userDetailsService;
    private final UserSessionRepository sessionRepository;
    private final PlatformSettingsService platformSettingsService;
    private final MongoTemplate mongoTemplate;
    private final AuthCache authCache;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        try {
            // Verify signature + expiry ONCE; null ⇔ the token is malformed / badly signed / expired
            // (previously re-parsed by every isValidX / extractJti / parseToken call).
            Claims claims = jwtUtil.tryParse(token);
            if (claims != null) {
                Object typeClaim = claims.get("type");
                String type = typeClaim instanceof String t ? t : null;

                // Skip ESIGN signing / view tokens — handled directly by the e-sign controllers
                if ("ESIGN".equals(type) || "ESIGN_VIEW".equals(type)) {
                    chain.doFilter(request, response);
                    return;
                }

                // Signer-portal token: authenticates ONLY on /api/signer/** — everywhere else the
                // request stays anonymous (→ 401), so a signer can never reach the main app's APIs.
                if ("SIGNER".equals(type)) {
                    if (isSignerPath(request)) {
                        String jti = claims.getId();
                        Optional<UserSession> session = activeSession(jti);
                        if (session.isPresent()) {
                            String email = claims.get("email", String.class);
                            SignerPrincipal principal = new SignerPrincipal(session.get().getUserId(), email);
                            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                                    principal, null, List.of(new SimpleGrantedAuthority("ROLE_SIGNER")));
                            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                            SecurityContextHolder.getContext().setAuthentication(auth);
                            request.setAttribute(RequestLoggingFilter.CALLER_ATTR, principal.getName());
                            request.setAttribute(RequestLoggingFilter.SESSION_ID_ATTR, jti);
                            touch(session.get());
                        }
                    }
                    chain.doFilter(request, response);
                    return;
                }

                // Any other valid token (user access token; MFA_CHALLENGE has no session → stays anonymous)
                String jti = claims.getId();

                // Single (cached) lookup — reuse the result for both auth and lastUsedAt update
                Optional<UserSession> sessionOpt = activeSession(jti);
                if (sessionOpt.isPresent()) {
                    UserSession session = sessionOpt.get();

                    String email = claims.get("email", String.class);
                    UserDetails ud = userDetailsService.loadUserCached(email);

                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(ud, null, ud.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);

                    // Expose caller identity for RequestLoggingFilter (SecurityContext is
                    // cleared by Spring Security before the outermost filter's finally block)
                    request.setAttribute(RequestLoggingFilter.CALLER_ATTR, email);
                    // Expose the session id (jti) so audit entries can be correlated per session.
                    request.setAttribute(RequestLoggingFilter.SESSION_ID_ATTR, jti);

                    touch(session);
                }
            }
        } catch (Exception ex) {
            // Log at DEBUG — this is expected for malformed / expired tokens
            log.debug("JWT authentication failed for {}: {}", request.getRequestURI(), ex.getMessage());
        }

        chain.doFilter(request, response);
    }

    /**
     * Uses the container-normalized, decoded servlet path — the path the request is actually
     * routed by — never the raw URI, so "/api/signer/../templates" style tricks can't make a
     * signer token authenticate on another endpoint (the firewall should already reject them;
     * this does not rely on it).
     */
    private static boolean isSignerPath(HttpServletRequest request) {
        String path = request.getServletPath() + (request.getPathInfo() != null ? request.getPathInfo() : "");
        return path.startsWith("/api/signer/") && !path.contains("..") && !path.contains(";");
    }

    /**
     * Returns the active session for {@code jti}, enforcing the platform session policy
     * (absolute + idle timeout). A timed-out session is deactivated and treated as absent.
     */
    private Optional<UserSession> activeSession(String jti) {
        if (jti == null) return Optional.empty();
        // Cached ≤ AuthCache.TTL; evicted on every UserSession save/delete (logout, revoke, …)
        // by AuthCacheEvictionListener, so revocation remains effectively immediate.
        UserSession session = authCache.sessions.get(jti,
                k -> sessionRepository.findByJtiAndActiveTrue(k).orElse(null));
        if (session == null || !session.isActive()) return Optional.empty();

        LocalDateTime now = LocalDateTime.now();
        // (settings are cached in-memory, so this does not add a DB read.)
        var sessionsCfg = platformSettingsService.getSettings().getSecurity().getSessions();
        int idleMinutes = sessionsCfg != null ? sessionsCfg.getIdleTimeoutMinutes() : 30;
        boolean timedOut =
                (session.getExpiresAt()  != null && session.getExpiresAt().isBefore(now))
             || (session.getLastUsedAt() != null && session.getLastUsedAt().plusMinutes(idleMinutes).isBefore(now));
        if (timedOut) {
            // Targeted update (not a full-document save of a possibly cached copy) + explicit evict.
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(session.getId())),
                    new Update().set("active", false),
                    UserSession.class);
            authCache.evictSession(jti);
            log.debug("Session jti='{}' timed out — not authenticating", jti);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /**
     * Update lastUsedAt — but throttle the write to at most once per minute. lastUsedAt
     * feeds both the session-listing display and the idle-timeout check; a DB write on
     * EVERY authenticated request would be pure overhead. A single-field $set (no
     * full-document save, so it can't overwrite concurrent changes to the session).
     */
    private void touch(UserSession session) {
        LocalDateTime now = LocalDateTime.now();
        if (session.getLastUsedAt() == null
                || session.getLastUsedAt().isBefore(now.minusSeconds(60))) {
            session.setLastUsedAt(now); // keeps the cached copy's throttle / idle check current
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(session.getId())),
                    new Update().set("lastUsedAt", now),
                    UserSession.class);
        }
    }
}
