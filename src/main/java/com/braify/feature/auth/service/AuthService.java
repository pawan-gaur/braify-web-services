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
import com.braify.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
        AppUser user = userRepository.findByEmail(req.getEmail())
                .orElseThrow(() -> {
                    log.warn("Login failed — user not found for email='{}'", req.getEmail());
                    return new RuntimeException("Invalid email or password");
                });

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
        return issueSession(user, req.getDeviceInfo(), ipAddress, false, mustSetupMfa);
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
        return issueSession(user, req.getDeviceInfo(), ipAddress, true, false);
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
                                     boolean mfaUsed, boolean mustSetupMfa) {
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
                           "mfa",        String.valueOf(mfaUsed)),
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
