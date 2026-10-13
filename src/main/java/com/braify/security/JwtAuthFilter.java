package com.braify.security;

import com.braify.config.RequestLoggingFilter;
import com.braify.feature.platform.service.PlatformSettingsService;
import com.braify.feature.session.model.UserSession;
import com.braify.feature.session.repository.UserSessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 *   <li>{@code lastUsedAt} is still updated synchronously (single save); a future
 *       optimisation could batch this with {@code @Async}.</li>
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
            // Skip ESIGN signing / view tokens — handled directly by the e-sign controllers
            if (jwtUtil.isValidSigningToken(token) || jwtUtil.isValidViewToken(token)) {
                chain.doFilter(request, response);
                return;
            }

            // Signer-portal token: authenticates ONLY on /api/signer/** — everywhere else the
            // request stays anonymous (→ 401), so a signer can never reach the main app's APIs.
            if (jwtUtil.isValidSignerToken(token)) {
                if (isSignerPath(request)) {
                    String jti = jwtUtil.extractJti(token);
                    Optional<UserSession> session = activeSession(jti);
                    if (session.isPresent()) {
                        String email = jwtUtil.parseToken(token).get("email", String.class);
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

            if (jwtUtil.isValid(token)) {
                String jti = jwtUtil.extractJti(token);

                // Single DB call — reuse the result for both auth and lastUsedAt update
                Optional<UserSession> sessionOpt = activeSession(jti);
                if (sessionOpt.isPresent()) {
                    UserSession session = sessionOpt.get();

                    String email = jwtUtil.parseToken(token).get("email", String.class);
                    UserDetails ud = userDetailsService.loadUserByUsername(email);

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
        Optional<UserSession> sessionOpt = sessionRepository.findByJtiAndActiveTrue(jti);
        if (sessionOpt.isEmpty()) return sessionOpt;

        UserSession session = sessionOpt.get();
        LocalDateTime now = LocalDateTime.now();
        // (settings are cached in-memory, so this does not add a DB read.)
        var sessionsCfg = platformSettingsService.getSettings().getSecurity().getSessions();
        int idleMinutes = sessionsCfg != null ? sessionsCfg.getIdleTimeoutMinutes() : 30;
        boolean timedOut =
                (session.getExpiresAt()  != null && session.getExpiresAt().isBefore(now))
             || (session.getLastUsedAt() != null && session.getLastUsedAt().plusMinutes(idleMinutes).isBefore(now));
        if (timedOut) {
            session.setActive(false);
            sessionRepository.save(session);
            log.debug("Session jti='{}' timed out — not authenticating", jti);
            return Optional.empty();
        }
        return sessionOpt;
    }

    /**
     * Update lastUsedAt — but throttle the write to at most once per minute. lastUsedAt
     * feeds both the session-listing display and the idle-timeout check; a DB write on
     * EVERY authenticated request would be pure overhead.
     */
    private void touch(UserSession session) {
        LocalDateTime now = LocalDateTime.now();
        if (session.getLastUsedAt() == null
                || session.getLastUsedAt().isBefore(now.minusSeconds(60))) {
            session.setLastUsedAt(now);
            sessionRepository.save(session);
        }
    }
}
