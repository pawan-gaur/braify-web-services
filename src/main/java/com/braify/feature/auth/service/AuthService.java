package com.braify.feature.auth.service;

import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.auth.dto.LoginRequest;
import com.braify.feature.auth.dto.LoginResponse;
import com.braify.feature.auth.dto.MfaVerifyRequest;
import com.braify.feature.user.model.AppUser;
import com.braify.shared.Feature;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.session.model.UserSession;
import com.braify.feature.user.repository.AppUserRepository;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.platform.service.PlatformSettingsService;
import com.braify.feature.session.repository.UserSessionRepository;
import com.braify.feature.signer.service.EmailOtpService;
import com.braify.feature.signer.service.SignerAuthService;
import com.braify.feature.signer.service.SignerEmails;
import com.braify.feature.signer.service.SignerInboxService;
import com.braify.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AppUserRepository     userRepository;
    private final UserSessionRepository sessionRepository;
    private final OrganizationRepository orgRepository;
    private final PasswordEncoder       passwordEncoder;
    private final JwtUtil               jwtUtil;
    private final AuditLogService       auditLogService;
    private final MfaService            mfaService;
    private final PlatformSettingsService platformSettingsService;
    private final PasswordPolicyService   passwordPolicyService;
    private final EmailOtpService         emailOtpService;
    private final SignerAuthService       signerAuthService;
    private final SignerInboxService      signerInboxService;

    private static final int MAX_SESSIONS = 3;

    /**
     * Bundles the JSON body returned to the client with the raw refresh token, which
     * the controller sets as an httpOnly cookie (never in the JSON body). {@code
     * refreshToken} is {@code null} for the MFA-challenge response, where no session
     * has been issued yet.
     */
    public record LoginResult(LoginResponse response, String refreshToken) {}

    public LoginResult login(LoginRequest req, String ipAddress) {
        log.info("Login attempt for email='{}'", req.getEmail());
        Optional<AppUser> userOpt = findUserByEmail(req.getEmail());
        if (userOpt.isEmpty()) {
            // No Braify account — the email may belong to an e-sign recipient who set a
            // signer-portal password. Same generic error either way (no account enumeration).
            String email = SignerEmails.normalize(req.getEmail());
            if (email != null) {
                Optional<LoginResult> signer =
                        signerAuthService.passwordLogin(email, req.getPassword(), req.getDeviceInfo(), ipAddress);
                if (signer.isPresent()) return signer.get();
            }
            log.warn("Login failed — user not found for email='{}'", req.getEmail());
            throw new RuntimeException("Invalid email or password");
        }
        AppUser user = userOpt.get();

        if (!user.isActive()) {
            log.warn("Login rejected — account disabled for email='{}'", req.getEmail());
            throw new RuntimeException("Account is disabled");
        }

        // Lockout: reject while the account is locked (platform security policy).
        LocalDateTime now = LocalDateTime.now();
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            long mins = Duration.between(now, user.getLockedUntil()).toMinutes() + 1;
            log.warn("Login blocked — account locked for email='{}' ({} min remaining)", req.getEmail(), mins);
            auditLogService.logFailureByUser(user.getId(), user.getEmail(),
                    AuditLog.Action.LOGIN, AuditLog.ResourceType.SESSION, user, "Login attempt while account locked");
            throw new RuntimeException("Account locked due to too many failed attempts. Try again in " + mins + " minute(s).");
        }

        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            log.warn("Login failed — invalid password for email='{}'", req.getEmail());
            registerFailedAttempt(user);
            throw new RuntimeException("Invalid email or password");
        }

        // Successful password — clear any failed-attempt / lock state.
        if (user.getFailedLoginAttempts() > 0 || user.getLockedUntil() != null) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            userRepository.save(user);
        }

        // Password is valid — now apply the MFA policy (org policy × user enrollment).
        MfaService.MfaRequirement mfaReq = mfaService.requirementAtLogin(user);
        if (mfaReq == MfaService.MfaRequirement.CHALLENGE) {
            log.info("MFA challenge required for '{}'", user.getEmail());
            // No session/token issued yet — only a short-lived challenge token.
            return new LoginResult(LoginResponse.builder()
                    .mfaRequired(true)
                    .mfaToken(jwtUtil.generateMfaChallengeToken(user))
                    .build(), null);
        }
        boolean mustSetupMfa = (mfaReq == MfaService.MfaRequirement.MUST_SETUP);
        return issueSession(user, req.getDeviceInfo(), ipAddress, false, mustSetupMfa, "PASSWORD",
                Boolean.TRUE.equals(user.getPasswordMailboxVerified()));
    }

    // ── Email OTP sign-in ─────────────────────────────────────────────────────

    /**
     * Emails a one-time sign-in code when the address belongs to an active Braify user or
     * to an e-sign recipient (has a signer account or any sent document). Unknown emails are
     * silently ignored — the caller always returns the same generic response.
     */
    public void requestLoginOtp(String rawEmail, String ipAddress) {
        String email = SignerEmails.normalize(rawEmail);
        if (email == null) throw new IllegalArgumentException("Email is required");

        boolean eligible = findUserByEmail(email).map(AppUser::isActive).orElse(false)
                || signerAuthService.hasAccount(email)
                || signerInboxService.hasAnyDocuments(email);
        if (!eligible) {
            log.info("OTP requested for unknown email='{}' — ignored", email);
            return;
        }
        emailOtpService.issue(email, ipAddress);
    }

    /**
     * Completes an email-OTP sign-in. A Braify user gets a full app session — the code
     * replaces the password only, so lockout and the MFA policy still apply exactly as for
     * a password login. A recipient without an AppUser gets a signer-portal session.
     */
    public LoginResult loginWithOtp(String rawEmail, String code, String deviceInfo, String ipAddress) {
        String email = SignerEmails.normalize(rawEmail);
        if (email == null) throw new IllegalArgumentException("Email is required");

        emailOtpService.verify(email, code);

        Optional<AppUser> userOpt = findUserByEmail(email).filter(AppUser::isActive);
        if (userOpt.isPresent()) {
            AppUser user = userOpt.get();
            LocalDateTime now = LocalDateTime.now();
            if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
                long mins = Duration.between(now, user.getLockedUntil()).toMinutes() + 1;
                throw new IllegalArgumentException(
                        "Account locked due to too many failed attempts. Try again in " + mins + " minute(s).");
            }
            MfaService.MfaRequirement mfaReq = mfaService.requirementAtLogin(user);
            if (mfaReq == MfaService.MfaRequirement.CHALLENGE) {
                log.info("OTP login for '{}' — MFA challenge required", user.getEmail());
                return new LoginResult(LoginResponse.builder()
                        .mfaRequired(true)
                        .mfaToken(jwtUtil.generateMfaChallengeToken(user, "EMAIL_OTP"))
                        .build(), null);
            }
            return issueSession(user, deviceInfo, ipAddress, false,
                    mfaReq == MfaService.MfaRequirement.MUST_SETUP, "EMAIL_OTP", true);
        }

        // No (active) Braify account — signer portal, if this email has anything to sign.
        if (signerAuthService.hasAccount(email) || signerInboxService.hasAnyDocuments(email)) {
            var account = signerAuthService.findOrCreate(email);
            if (!account.isActive()) throw new IllegalArgumentException("Account is disabled");
            return signerAuthService.issueSession(account, deviceInfo, ipAddress, true);
        }
        throw new IllegalArgumentException("Invalid or expired code. Request a new one.");
    }

    /**
     * Whether the user has a password they can sign in with. False when none was ever stored,
     * or when it is still the random placeholder of an unaccepted invite.
     */
    public static boolean hasUsablePassword(AppUser user) {
        return user.getPassword() != null && !user.getPassword().isBlank() && !user.isMustChangePassword();
    }

    /** Exact match first (existing rows), then the normalized form. */
    private Optional<AppUser> findUserByEmail(String email) {
        if (email == null) return Optional.empty();
        Optional<AppUser> exact = userRepository.findByEmail(email.trim());
        if (exact.isPresent()) return exact;
        String normalized = SignerEmails.normalize(email);
        return normalized != null && !normalized.equals(email.trim())
                ? userRepository.findByEmail(normalized)
                : Optional.empty();
    }

    /**
     * Completes a login that required MFA. Validates the short-lived challenge token,
     * verifies the TOTP/recovery code, then issues the real session — the same path a
     * non-MFA login takes.
     */
    public LoginResult verifyMfaAndLogin(MfaVerifyRequest req, String ipAddress) {
        if (req.getMfaToken() == null || !jwtUtil.isValidMfaChallengeToken(req.getMfaToken())) {
            throw new RuntimeException("Your verification session expired. Please sign in again.");
        }
        String userId = jwtUtil.extractUserId(req.getMfaToken());
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (!user.isActive()) throw new RuntimeException("Account is disabled");

        if (!mfaService.verifyCode(user, req.getCode())) {
            log.warn("MFA verification failed for '{}'", user.getEmail());
            auditLogService.logFailureByUser(user.getId(), user.getEmail(),
                    AuditLog.Action.LOGIN, AuditLog.ResourceType.SESSION, user, "Invalid MFA code");
            throw new RuntimeException("Invalid verification code");
        }
        boolean viaEmailOtp = "EMAIL_OTP".equals(
                jwtUtil.parseToken(req.getMfaToken()).get("firstFactor", String.class));
        return issueSession(user, req.getDeviceInfo(), ipAddress, true, false,
                viaEmailOtp ? "EMAIL_OTP+MFA" : "PASSWORD+MFA",
                viaEmailOtp || Boolean.TRUE.equals(user.getPasswordMailboxVerified()));
    }

    /**
     * Records a failed login attempt and locks the account once the configured
     * threshold (platform security policy) is reached.
     */
    private void registerFailedAttempt(AppUser user) {
        var lockout = platformSettingsService.getSettings().getSecurity().getLockout();
        int maxAttempts = lockout != null ? lockout.getMaxFailedAttempts() : 5;
        int lockMinutes = lockout != null ? lockout.getLockoutMinutes()    : 30;

        int attempts = user.getFailedLoginAttempts() + 1;
        if (attempts >= maxAttempts) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(LocalDateTime.now().plusMinutes(lockMinutes));
            log.warn("Account locked for '{}' after {} failed attempts ({} min)",
                    user.getEmail(), maxAttempts, lockMinutes);
            auditLogService.logFailureByUser(user.getId(), user.getEmail(),
                    AuditLog.Action.LOGIN, AuditLog.ResourceType.SESSION, user,
                    "Account locked after " + maxAttempts + " failed login attempts");
        } else {
            user.setFailedLoginAttempts(attempts);
        }
        userRepository.save(user);
    }

    /**
     * Shared session-issuing path: enforce session limit, mint the JWT, persist the
     * {@link UserSession}, audit the LOGIN, and build the full {@link LoginResponse}.
     */
    private LoginResult issueSession(AppUser user, String deviceInfo, String ipAddress,
                                     boolean mfaUsed, boolean mustSetupMfa, String method,
                                     boolean emailVerified) {
        // Password expiry (platform policy): force a change at login when overdue.
        if (!user.isMustChangePassword() && passwordPolicyService.isExpired(user)) {
            user.setMustChangePassword(true);
            userRepository.save(user);
            log.info("Password expired for '{}' — forcing change at login", user.getEmail());
        }

        // Session policy (platform settings): max concurrent + absolute timeout.
        var sessionsCfg = platformSettingsService.getSettings().getSecurity().getSessions();
        int maxConcurrent = sessionsCfg != null ? sessionsCfg.getMaxConcurrent()       : MAX_SESSIONS;
        int sessionHours  = sessionsCfg != null ? sessionsCfg.getSessionTimeoutHours() : 8;

        // Enforce session limit: if >= maxConcurrent active, revoke oldest
        List<UserSession> activeSessions =
                sessionRepository.findByUserIdAndActiveTrueOrderByCreatedAtAsc(user.getId());
        if (activeSessions.size() >= maxConcurrent) {
            int toRevoke = activeSessions.size() - maxConcurrent + 1;
            log.info("Session limit reached for user '{}' — revoking {} oldest session(s)", user.getEmail(), toRevoke);
            activeSessions.stream().limit(toRevoke).forEach(s -> {
                s.setActive(false);
                sessionRepository.save(s);
            });
        }

        // Generate token and persist session
        String token = jwtUtil.generateToken(user);
        String jti   = jwtUtil.extractJti(token);

        // Opaque refresh token — returned to the client (cookie); only its hash is stored.
        String rawRefreshToken = jwtUtil.generateRefreshToken();

        // Absolute session length is the configured timeout, capped by the JWT's own expiry.
        // NOTE: the JWT (access) is now short-lived (~30 min) and re-minted via refresh, so the
        // session's absolute window is driven by the platform sessionHours policy, not the JWT.
        LocalDateTime sessionExpiry = LocalDateTime.now().plusHours(sessionHours);

        UserSession session = UserSession.builder()
                .userId(user.getId())
                .jti(jti)
                .refreshTokenHash(jwtUtil.hashRefreshToken(rawRefreshToken))
                .organizationId(user.getOrganizationId())
                .userRole(user.getRole().name())
                .deviceInfo(deviceInfo)
                .ipAddress(ipAddress)
                .active(true)
                .emailVerified(emailVerified)
                .expiresAt(sessionExpiry)
                .lastUsedAt(LocalDateTime.now())
                .build();
        sessionRepository.save(session);

        // Audit: record login event against SESSION resource
        auditLogService.logByUser(
                session.getId(), null,
                AuditLog.Action.LOGIN, AuditLog.ResourceType.SESSION,
                0, Map.of("ip",         ipAddress  != null ? ipAddress : "unknown",
                           "deviceInfo", deviceInfo != null ? deviceInfo : "",
                           "mfa",        String.valueOf(mfaUsed),
                           "method",     method),
                user);

        log.info("Login successful for user '{}' (role={}, mfa={})", user.getEmail(), user.getRole(), mfaUsed);
        return new LoginResult(buildLoginResponse(user, token, mustSetupMfa), rawRefreshToken);
    }

    /**
     * Exchanges a valid refresh token for a fresh access token, rotating both the
     * access-token {@code jti} and the refresh token on the same {@link UserSession}.
     * Enforces the same absolute + idle limits as {@link com.braify.security.JwtAuthFilter}.
     *
     * @throws RuntimeException if the token is missing/unknown or the session has
     *         been revoked, hit its absolute expiry, or timed out on idle. The
     *         controller maps any failure here to HTTP 401.
     */
    public LoginResult refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new RuntimeException("Missing refresh token");
        }
        String hash = jwtUtil.hashRefreshToken(rawRefreshToken);
        UserSession session = sessionRepository.findByRefreshTokenHashAndActiveTrue(hash)
                .orElseThrow(() -> new RuntimeException("Invalid or expired refresh token"));

        LocalDateTime now = LocalDateTime.now();

        // Absolute session timeout.
        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(now)) {
            deactivate(session, "absolute timeout");
            throw new RuntimeException("Session expired");
        }
        // Idle timeout (same policy the JwtAuthFilter enforces).
        var sessionsCfg = platformSettingsService.getSettings().getSecurity().getSessions();
        int idleMinutes = sessionsCfg != null ? sessionsCfg.getIdleTimeoutMinutes() : 30;
        if (session.getLastUsedAt() != null
                && session.getLastUsedAt().plusMinutes(idleMinutes).isBefore(now)) {
            deactivate(session, "idle timeout");
            throw new RuntimeException("Session idle timeout");
        }

        // Signer-portal session — the session row points at a SignerAccount, not an AppUser.
        if (SignerAuthService.PRINCIPAL_TYPE.equals(session.getPrincipalType())) {
            return signerAuthService.refresh(session);
        }

        AppUser user = userRepository.findById(session.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (!user.isActive()) {
            deactivate(session, "account disabled");
            throw new RuntimeException("Account is disabled");
        }

        // Rotate: new access token (new jti) + new refresh token on the SAME session row.
        String newToken       = jwtUtil.generateToken(user);
        String newRawRefresh  = jwtUtil.generateRefreshToken();
        session.setJti(jwtUtil.extractJti(newToken));
        session.setRefreshTokenHash(jwtUtil.hashRefreshToken(newRawRefresh));
        session.setLastUsedAt(now);
        sessionRepository.save(session);

        log.debug("Refreshed session for user '{}' (jti rotated)", user.getEmail());
        // mustSetupMfa is a login-time gate; a refreshed session has already passed it.
        return new LoginResult(buildLoginResponse(user, newToken, false), newRawRefresh);
    }

    private void deactivate(UserSession session, String reason) {
        session.setActive(false);
        sessionRepository.save(session);
        log.debug("Session jti='{}' deactivated on refresh — {}", session.getJti(), reason);
    }

    /** Builds the client-facing login payload (token + user + org features). */
    private LoginResponse buildLoginResponse(AppUser user, String token, boolean mustSetupMfa) {
        Organization org = user.getOrganizationId() != null
                ? orgRepository.findById(user.getOrganizationId()).orElse(null)
                : null;
        String orgName = org != null ? org.getName() : null;

        // PLATFORM_ADMIN sees all features; regular users see their org's assigned features
        boolean isPlatformAdmin = user.getRole() == AppUser.Role.PLATFORM_ADMIN;
        List<String> features = isPlatformAdmin
                ? Feature.allKeys()
                : (org != null && org.getFeatures() != null ? org.getFeatures() : List.of());

        return LoginResponse.builder()
                .token(token)
                .userId(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .role(user.getRole().name())
                .organizationId(user.getOrganizationId())
                .organizationName(orgName)
                .profilePicture(user.getProfilePicture())
                .mustChangePassword(user.isMustChangePassword())
                .hasPassword(hasUsablePassword(user))
                .features(features)
                .mustSetupMfa(mustSetupMfa)
                .build();
    }

    public void logout(String jti) {
        log.info("Logout: revoking session jti='{}'", jti);
        sessionRepository.findByJtiAndActiveTrue(jti).ifPresent(s -> {
            s.setActive(false);
            sessionRepository.save(s);
            log.info("Session revoked for user '{}'", s.getUserId());
            // Audit: record logout — look up user once for full details
            userRepository.findById(s.getUserId()).ifPresent(u ->
                auditLogService.logByUser(
                        s.getId(), null,
                        AuditLog.Action.LOGOUT, AuditLog.ResourceType.SESSION,
                        0, null, u)
            );
        });
    }
}
