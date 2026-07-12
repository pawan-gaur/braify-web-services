package com.braify.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Returns <strong>401 Unauthorized</strong> (not 403) whenever a request reaches a
 * protected endpoint without a valid authentication — i.e. no token, or a
 * malformed / expired / revoked JWT that {@link JwtAuthFilter} could not
 * authenticate.
 *
 * <p>Spring Security's default for a STATELESS chain with no form-login /
 * http-basic is {@code Http403ForbiddenEntryPoint}, which incorrectly maps
 * "not authenticated" to 403 Forbidden. That made an expired session look
 * identical to a genuine access-denied error on the client. By wiring this
 * entry point we restore the correct semantics:
 * <ul>
 *   <li><b>401</b> — the caller is not authenticated (token missing / expired /
 *       invalid). The frontend interceptor clears the token and redirects to
 *       {@code /login}.</li>
 *   <li><b>403</b> — the caller <em>is</em> authenticated but lacks the required
 *       role/permission (handled by {@code GlobalExceptionHandler} /
 *       {@code AccessDeniedHandler}).</li>
 * </ul>
 *
 * <p>The JSON body matches the shape produced by {@code GlobalExceptionHandler}
 * ({@code {status, message, timestamp}}) so the frontend can parse it uniformly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        log.debug("Unauthenticated request to {} — returning 401: {}",
                request.getRequestURI(), authException.getMessage());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status",    HttpStatus.UNAUTHORIZED.value());
        body.put("message",   "Authentication required — your session may have expired. Please log in again.");
        body.put("timestamp", Instant.now().toString());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
