package com.braify.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Logs a one-line summary of every inbound HTTP request at INFO level:
 *
 * <pre>
 * [REQUEST] POST   /api/auth/login          → 200  in  34ms [anonymous]
 * [REQUEST] GET    /api/organizations        → 200  in  12ms [admin@braify.io]
 * [REQUEST] POST   /api/external/files       → 201  in  89ms [key:brfy_a1b2]
 * [REQUEST] DELETE /api/templates/abc123     → 403  in   5ms [anonymous]
 * </pre>
 *
 * <h3>Design</h3>
 * <ul>
 *   <li>Registered at {@link Ordered#HIGHEST_PRECEDENCE} so it wraps the <em>entire</em>
 *       filter chain, capturing all requests including those rejected by Spring Security
 *       (401 / 403) and exceptions from the dispatcher servlet.</li>
 *   <li>The caller identity is populated by {@link com.braify.security.JwtAuthFilter} and
 *       {@link com.braify.security.ApiKeyAuthFilter} via the {@link #CALLER_ATTR} request
 *       attribute immediately after successful authentication.  We cannot rely on
 *       {@code SecurityContextHolder} here because Spring Security clears the context
 *       before this filter's {@code finally} block runs.</li>
 *   <li>Noisy internal paths (Swagger, OpenAPI, actuator) are skipped.</li>
 * </ul>
 *
 * <h3>Security</h3>
 * This filter <strong>never</strong> logs request bodies, {@code Authorization} headers,
 * passwords, tokens, API keys, or any other sensitive field.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    /**
     * Request attribute key written by the authentication filters so we can
     * identify the caller after the SecurityContext has been cleared.
     *
     * <ul>
     *   <li>JWT user   → email address, e.g. {@code "admin@braify.io"}</li>
     *   <li>API key    → {@code "key:<prefix>"}, e.g. {@code "key:brfy_a1b2"}</li>
     *   <li>Not set    → logged as {@code "anonymous"}</li>
     * </ul>
     */
    public static final String CALLER_ATTR = "braify.caller";

    /** Paths that are too high-frequency or uninteresting to log. */
    private static final String[] SKIP_PREFIXES = {
            "/v3/api-docs",
            "/swagger-ui",
            "/actuator"
    };

    @Override
    protected void doFilterInternal(HttpServletRequest  request,
                                    HttpServletResponse response,
                                    FilterChain         chain) throws ServletException, IOException {
        if (shouldSkip(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            long   elapsed = System.currentTimeMillis() - start;
            int    status  = response.getStatus();
            String caller  = callerFrom(request);
            String method  = String.format("%-6s", request.getMethod());

            if (status >= 500) {
                log.warn("[REQUEST] {} {} → {} in {}ms [{}]",
                        method, request.getRequestURI(), status, elapsed, caller);
            } else {
                log.info("[REQUEST] {} {} → {} in {}ms [{}]",
                        method, request.getRequestURI(), status, elapsed, caller);
            }
        }
    }

    /** Reads the caller identity set by the auth filters, falling back to "anonymous". */
    private static String callerFrom(HttpServletRequest request) {
        Object attr = request.getAttribute(CALLER_ATTR);
        return (attr != null) ? attr.toString() : "anonymous";
    }

    private static boolean shouldSkip(String path) {
        if (path == null) return false;
        for (String prefix : SKIP_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }
}
