package com.braify.security;

import com.braify.feature.apikey.model.OrgApiKey;
import com.braify.feature.apikey.service.OrgApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Servlet filter that authenticates requests carrying an {@code X-API-Key} header.
 *
 * <p>Processing order:
 * <ol>
 *   <li>If the header is absent, the filter is a no-op and the next filter runs.</li>
 *   <li>If the SecurityContext is already populated (JWT already authenticated), pass through.</li>
 *   <li>Validate the key via {@link OrgApiKeyService#validateKey(String)}.</li>
 *   <li>Detect the target feature from the URL path.</li>
 *   <li>Verify that both the key and the organisation allow that feature.</li>
 *   <li>On success: set an {@link ApiKeyPrincipal} with ROLE_API_KEY into the context.</li>
 *   <li>On any failure: return a JSON 401/403 and stop the chain.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME      = "X-API-Key";
    private static final ObjectMapper MAPPER      = new ObjectMapper();

    private final OrgApiKeyService orgApiKeyService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String apiKey = request.getHeader(HEADER_NAME);

        // No API key present — let the JWT filter handle it
        if (apiKey == null || apiKey.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        // JWT has already authenticated this request — honour it
        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }

        try {
            // Validate key (throws ResponseStatusException on failure)
            OrgApiKey key = orgApiKeyService.validateKey(apiKey);

            // Detect feature from URL
            String path    = request.getRequestURI();
            String feature = detectFeature(path);

            // Check the key is permitted to access this feature
            if (feature != null) {
                if (!key.getAllowedFeatures().contains(feature)) {
                    writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                            Map.of("error", "Feature not allowed for this API key"));
                    return;
                }
            }

            // Build principal and set into SecurityContext
            ApiKeyPrincipal principal = new ApiKeyPrincipal(
                    key.getOrgId(),
                    key.getId(),
                    key.getKeyPrefix(),
                    key.getAllowedFeatures()
            );

            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_API_KEY"))
            );
            SecurityContextHolder.getContext().setAuthentication(auth);

            // Async usage tracking — non-blocking
            orgApiKeyService.trackUsage(
                    key.getOrgId(),
                    key.getId(),
                    key.getKeyPrefix(),
                    feature != null ? feature : "UNKNOWN",
                    path,
                    request.getMethod(),
                    HttpServletResponse.SC_OK   // optimistic; actual code logged in controller if needed
            );

            chain.doFilter(request, response);

        } catch (org.springframework.web.server.ResponseStatusException ex) {
            int status = ex.getStatusCode().value();
            if (status == HttpServletResponse.SC_FORBIDDEN) {
                writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                        Map.of("error", ex.getReason() != null ? ex.getReason() : "Forbidden"));
            } else {
                writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                        Map.of("error", ex.getReason() != null ? ex.getReason() : "Invalid or expired API key"));
            }
        } catch (Exception ex) {
            log.warn("API key authentication failed: {}", ex.getMessage());
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                    Map.of("error", "Invalid or expired API key"));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Infers the required feature from the request URL path.
     *
     * <ul>
     *   <li>{@code /api/external/pdf/**}   → PDF_TEMPLATES</li>
     *   <li>{@code /api/external/email/**} → EMAIL_TEMPLATES</li>
     *   <li>{@code /api/external/esign/**} → E_SIGN</li>
     * </ul>
     */
    private static String detectFeature(String path) {
        if (path == null) return null;
        if (path.startsWith("/api/external/pdf"))   return "PDF_TEMPLATES";
        if (path.startsWith("/api/external/email"))  return "EMAIL_TEMPLATES";
        if (path.startsWith("/api/external/esign"))  return "E_SIGN";
        return null;
    }

    private static void writeJson(HttpServletResponse response, int status, Map<String, ?> body)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        MAPPER.writeValue(response.getOutputStream(), body);
    }
}
