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
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

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

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest req,
                                               HttpServletRequest httpReq) {
        log.info("POST /api/auth/login email='{}'", req.getEmail());
        String ip = extractClientIp(httpReq);
        return ResponseEntity.ok(authService.login(req, ip));
    }

    // ── MFA ───────────────────────────────────────────────────────────────────

    /** Step 2 of login when MFA is required. Public (no session yet) — the mfaToken authorises it. */
    @PostMapping("/login/mfa")
    public ResponseEntity<LoginResponse> loginMfa(@RequestBody MfaVerifyRequest req,
                                                  HttpServletRequest httpReq) {
        log.info("POST /api/auth/login/mfa");
        return ResponseEntity.ok(authService.verifyMfaAndLogin(req, extractClientIp(httpReq)));
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
    public ResponseEntity<Void> logout(@RequestHeader("Authorization") String authHeader) {
        log.info("POST /api/auth/logout");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String jti = jwtUtil.extractJti(authHeader.substring(7));
            authService.logout(jti);
        }
        log.info("Logout successful");
        return ResponseEntity.noContent().build();
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
        if (rawToken == null || newPassword == null || newPassword.length() < 6) {
            log.warn("Accept-invite validation failed: token or password missing/invalid");
            throw new RuntimeException("Token and a password of at least 6 characters are required");
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
        user.setPassword(passwordEncoder.encode(newPassword));
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
        if (rawToken == null || newPassword == null || newPassword.length() < 6) {
            log.warn("Reset-password validation failed: token or password missing/invalid");
            throw new RuntimeException("Token and a password of at least 6 characters are required");
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
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);

        it.setUsed(true);
        it.setUsedAt(LocalDateTime.now());
        tokenRepository.save(it);

        log.info("Password reset completed for user '{}'", user.getEmail());
        return ResponseEntity.ok(Map.of("message", "Password reset successfully. You can now log in."));
    }
}
