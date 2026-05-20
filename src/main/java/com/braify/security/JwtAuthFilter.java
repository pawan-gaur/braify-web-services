package com.braify.security;

import com.braify.config.RequestLoggingFilter;
import com.braify.feature.session.model.UserSession;
import com.braify.feature.session.repository.UserSessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
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
            // Skip ESIGN signing tokens — handled directly by ESignClientController
            if (jwtUtil.isValidSigningToken(token)) {
                chain.doFilter(request, response);
                return;
            }

            if (jwtUtil.isValid(token)) {
                String jti = jwtUtil.extractJti(token);

                // Single DB call — reuse the result for both auth and lastUsedAt update
                Optional<UserSession> sessionOpt = sessionRepository.findByJtiAndActiveTrue(jti);
                if (sessionOpt.isPresent()) {
                    String email = jwtUtil.parseToken(token).get("email", String.class);
                    UserDetails ud = userDetailsService.loadUserByUsername(email);

                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(ud, null, ud.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);

                    // Expose caller identity for RequestLoggingFilter (SecurityContext is
                    // cleared by Spring Security before the outermost filter's finally block)
                    request.setAttribute(RequestLoggingFilter.CALLER_ATTR, email);

                    // Update lastUsedAt using the session we already fetched
                    UserSession session = sessionOpt.get();
                    session.setLastUsedAt(LocalDateTime.now());
                    sessionRepository.save(session);
                }
            }
        } catch (Exception ex) {
            // Log at DEBUG — this is expected for malformed / expired tokens
            log.debug("JWT authentication failed for {}: {}", request.getRequestURI(), ex.getMessage());
        }

        chain.doFilter(request, response);
    }
}
