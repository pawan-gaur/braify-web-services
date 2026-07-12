package com.braify.feature.auth.controller;

import com.braify.feature.auth.dto.LoginRequest;
import com.braify.feature.auth.dto.LoginResponse;
import com.braify.feature.auth.dto.MfaCodeRequest;
import com.braify.feature.auth.dto.MfaRecoveryCodesResponse;
import com.braify.feature.auth.dto.MfaSetupResponse;
import com.braify.feature.auth.dto.MfaStatusResponse;
import com.braify.feature.auth.dto.MfaVerifyRequest;
import com.braify.feature.auth.service.MfaService;
import com.braify.feature.user.dto.UserResponse;
import com.braify.feature.user.model.AppUser;
import com.braify.feature.auth.model.InvitationToken;
import com.braify.feature.user.repository.AppUserRepository;
import com.braify.feature.auth.repository.InvitationTokenRepository;
import com.braify.security.JwtUtil;
import com.braify.security.UserDetailsImpl;
import com.braify.feature.auth.service.AuthService;
import com.braify.feature.auth.service.EmailInviteService;
import com.braify.feature.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtUtil jwtUtil;
    private final InvitationTokenRepository tokenRepository;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailInviteService emailInviteService;
    private final UserService userService;
    private final MfaService mfaService;
    private final com.braify.feature.auth.service.PasswordPolicyService passwordPolicyService;

    /** Name of the httpOnly refresh-token cookie. Scoped to /api/auth so it is only ever sent to auth endpoints. */
    private static final String REFRESH_COOKIE = "refresh_token";
    private static final String REFRESH_COOKIE_PATH = "/api/auth";

    @Value("${app.cookie-secure:false}")
    private boolean cookieSecure;

    @Value("${jwt.refresh-token-hours:8}")
    private int refreshTokenHours;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest req,
                                               HttpServletRequest httpReq,
                                               HttpServletResponse httpRes) {
        log.info("POST /api/auth/login email='{}'", req.getEmail());
        String ip = extractClientIp(httpReq);
        AuthService.LoginResult result = authService.login(req, ip);
        setRefreshCookie(httpRes, result.refreshToken());   // no-op for the MFA-challenge response
        return ResponseEntity.ok(result.response());
    }

    /**
     * Exchanges the httpOnly refresh cookie for a fresh access token (rotating both).
     * Public — the cookie is the credential. Returns 401 (and clears the cookie) when
     * the session is gone, so the client falls back to /login.
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
                                     HttpServletResponse httpRes) {
        try {
            AuthService.LoginResult result = authService.refresh(refreshToken);
            setRefreshCookie(httpRes, result.refreshToken());
            return ResponseEntity.ok(result.response());
        } catch (RuntimeException ex) {
            log.info("Refresh rejected: {}", ex.getMessage());
            clearRefreshCookie(httpRes);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "status",    HttpStatus.UNAUTHORIZED.value(),
                    "message",   "Session expired. Please log in again.",
                    "timestamp", Instant.now().toString()));
        }
    }

    // ── MFA ───────────────────────────────────────────────────────────────────

    /** Step 2 of login when MFA is required. Public (no session yet) — the mfaToken authorises it. */
    @PostMapping("/login/mfa")
    public ResponseEntity<LoginResponse> loginMfa(@RequestBody MfaVerifyRequest req,
                                                  HttpServletRequest httpReq,
                                                  HttpServletResponse httpRes) {
        log.info("POST /api/auth/login/mfa");
        AuthService.LoginResult result = authService.verifyMfaAndLogin(req, extractClientIp(httpReq));
        setRefreshCookie(httpRes, result.refreshToken());
        return ResponseEntity.ok(result.response());
    }

    /** Begin enrollment — returns the QR + secret. Requires an authenticated session. */
    @PostMapping("/mfa/setup")
    public ResponseEntity<MfaSetupResponse> mfaSetup(Authentication auth) {
        return ResponseEntity.ok(mfaService.setup(currentUser(auth)));
    }

    /** Verify the first code and turn MFA on — returns one-time recovery codes. */
    @PostMapping("/mfa/enable")
    public ResponseEntity<MfaRecoveryCodesResponse> mfaEnable(@RequestBody MfaCodeRequest req,
                                                              Authentication auth) {
        return ResponseEntity.ok(new MfaRecoveryCodesResponse(
                mfaService.enable(currentUser(auth), req.getCode())));
    }

    /** Turn MFA off (rejected if the org policy is REQUIRED). */
    @PostMapping("/mfa/disable")
    public ResponseEntity<Void> mfaDisable(@RequestBody MfaCodeRequest req, Authentication auth) {
        mfaService.disable(currentUser(auth), req.getCode());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/mfa/status")
    public ResponseEntity<MfaStatusResponse> mfaStatus(Authentication auth) {
        return ResponseEntity.ok(mfaService.status(currentUser(auth)));
    }

    @PostMapping("/mfa/recovery-codes/regenerate")
    public ResponseEntity<MfaRecoveryCodesResponse> mfaRegenerate(@RequestBody MfaCodeRequest req,
                                                                  Authentication auth) {
        return ResponseEntity.ok(new MfaRecoveryCodesResponse(
                mfaService.regenerateRecoveryCodes(currentUser(auth), req.getCode())));
    }

    private AppUser currentUser(Authentication auth) {
        return ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
    }

    /**
     * Resolves the real client IP address when the app is behind a reverse proxy (Nginx).
     * Priority: X-Forwarded-For → X-Real-IP → getRemoteAddr()
     *
     * X-Forwarded-For may contain a comma-separated chain (client, proxy1, proxy2…);
     * the first entry is always the originating client.
     */
    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // Take the first IP in the chain — that's the real client
            return xff.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                       HttpServletResponse httpRes) {
        log.info("POST /api/auth/logout");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                String jti = jwtUtil.extractJti(authHeader.substring(7));
                authService.logout(jti);
            } catch (Exception e) {
                // Access token may already be expired — nothing to revoke by jti, but we still
                // clear the cookie below so the client is fully logged out.
                log.debug("Logout: could not parse access-token jti: {}", e.getMessage());
            }
        }
        clearRefreshCookie(httpRes);
        log.info("Logout successful");
        return ResponseEntity.noContent().build();
    }

    // ── Refresh-cookie helpers ──────────────────────────────────────────────

    /** Sets the rotating refresh token as an httpOnly cookie. No-op when {@code rawToken} is null. */
    private void setRefreshCookie(HttpServletResponse res, String rawToken) {
        if (rawToken == null) return;
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, rawToken)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(Duration.ofHours(refreshTokenHours))
                .build();
        res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /** Expires the refresh cookie (logout / failed refresh). */
    private void clearRefreshCookie(HttpServletResponse res) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(0)
                .build();
        res.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /** Returns the current user's full profile. */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(Authentication auth) {
        log.debug("GET /api/auth/me");
        UserDetailsImpl ud = (UserDetailsImpl) auth.getPrincipal();
        return ResponseEntity.ok(userService.toResponse(ud.getAppUser()));
    }

    // ── Invite / Password reset ──────────────────────────────────────────────

    /**
     * Validate an invitation or password-reset token.
     * Returns the user's email so the frontend can pre-fill it.
     */
    @GetMapping("/validate-token")
    public ResponseEntity<Map<String, Object>> validateToken(@RequestParam String token) {
        log.info("GET /api/auth/validate-token");
        InvitationToken it = tokenRepository.findByTokenAndUsedFalse(token)
                .orElseThrow(() -> new RuntimeException("Invalid or expired token"));
        if (it.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Token has expired");
        }
        AppUser user = userRepository.findById(it.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found"));
        return ResponseEntity.ok(Map.of(
                "email", user.getEmail(),
                "firstName", user.getFirstName(),
                "type", it.getType().name()
        ));
    }

    /**
     * Accept an invitation — set the password for the first time.
     * Body: { token, password }
     */
    @PostMapping("/accept-invite")
    public ResponseEntity<Map<String, String>> acceptInvite(@RequestBody Map<String, String> body) {
        log.info("POST /api/auth/accept-invite");
        String rawToken = body.get("token");
        String newPassword = body.get("password");
        if (rawToken == null || newPassword == null) {
            log.warn("Accept-invite validation failed: token or password missing");
            throw new RuntimeException("Token and a password are required");
        }

        InvitationToken it = tokenRepository.findByTokenAndUsedFalse(rawToken)
                .orElseThrow(() -> new RuntimeException("Invalid or expired token"));
        if (it.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Invitation link has expired. Please ask an admin to resend the invite.");
        }
        if (it.getType() != InvitationToken.TokenType.INVITE) {
            throw new RuntimeException("Invalid token type");
        }

        AppUser user = userRepository.findById(it.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found"));
        // Enforce platform password policy (length / complexity / re-use) + history.
        passwordPolicyService.applyNewPassword(user, newPassword);
        user.setMustChangePassword(false);
        userRepository.save(user);

        it.setUsed(true);
        it.setUsedAt(LocalDateTime.now());
        tokenRepository.save(it);

        log.info("Invite accepted for user '{}'", user.getEmail());
        return ResponseEntity.ok(Map.of("message", "Password set successfully. You can now log in."));
    }

    /**
     * Request a password-reset email.
     * Body: { email }
     * Always returns 200 so we don't leak whether an email is registered.
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@RequestBody Map<String, String> body) {
        log.info("POST /api/auth/forgot-password");
        String email = body.get("email");
        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.isActive()) {
                emailInviteService.sendPasswordReset(user);
            }
        });
        return ResponseEntity.ok(Map.of("message",
                "If that email is registered you'll receive a reset link shortly."));
    }

    /**
     * Complete a password reset.
     * Body: { token, password }
     */
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@RequestBody Map<String, String> body) {
        log.info("POST /api/auth/reset-password");
        String rawToken = body.get("token");
        String newPassword = body.get("password");
        if (rawToken == null || newPassword == null) {
            log.warn("Reset-password validation failed: token or password missing");
            throw new RuntimeException("Token and a password are required");
        }

        InvitationToken it = tokenRepository.findByTokenAndUsedFalse(rawToken)
                .orElseThrow(() -> new RuntimeException("Invalid or expired token"));
        if (it.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Reset link has expired. Please request a new one.");
        }
        if (it.getType() != InvitationToken.TokenType.PASSWORD_RESET) {
            throw new RuntimeException("Invalid token type");
        }

        AppUser user = userRepository.findById(it.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found"));
        // Enforce platform password policy (length / complexity / re-use) + history.
        passwordPolicyService.applyNewPassword(user, newPassword);
        user.setMustChangePassword(false);
        userRepository.save(user);

        it.setUsed(true);
        it.setUsedAt(LocalDateTime.now());
        tokenRepository.save(it);

        log.info("Password reset completed for user '{}'", user.getEmail());
        return ResponseEntity.ok(Map.of("message", "Password reset successfully. You can now log in."));
    }
}
