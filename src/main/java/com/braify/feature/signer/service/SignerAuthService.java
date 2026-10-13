package com.braify.feature.signer.service;

import com.braify.feature.auth.dto.LoginResponse;
import com.braify.feature.auth.service.AuthService;
import com.braify.feature.auth.service.PasswordPolicyService;
import com.braify.feature.platform.service.PlatformSettingsService;
import com.braify.feature.session.model.UserSession;
import com.braify.feature.session.repository.UserSessionRepository;
import com.braify.feature.signer.model.SignerAccount;
import com.braify.feature.signer.repository.SignerAccountRepository;
import com.braify.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Sessions for external e-sign recipients (no AppUser account).
 *
 * <p>Signer sessions reuse the {@link UserSession} machinery — same refresh cookie,
 * rotation, absolute/idle timeouts and revocation — but carry
 * {@code principalType = "SIGNER"} and mint a {@code type = "SIGNER"} access token that
 * {@link com.braify.security.JwtAuthFilter} honours only on {@code /api/signer/**}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignerAuthService {

    public static final String PRINCIPAL_TYPE = "SIGNER";

    private final SignerAccountRepository accountRepository;
    private final UserSessionRepository   sessionRepository;
    private final JwtUtil                 jwtUtil;
    private final PasswordEncoder         passwordEncoder;
    private final PasswordPolicyService   passwordPolicyService;
    private final PlatformSettingsService platformSettingsService;
    private final SignerInboxService      inboxService;

    public boolean hasAccount(String email) {
        return accountRepository.findByEmail(email).isPresent();
    }

    /** Returns the account for {@code email}, creating it on first sign-in. */
    public SignerAccount findOrCreate(String email) {
        return accountRepository.findByEmail(email).orElseGet(() -> {
            SignerAccount acc = SignerAccount.builder()
                    .email(email)
                    .name(inboxService.latestSignerName(email))
                    .build();
            log.info("Creating signer account for '{}'", email);
            return accountRepository.save(acc);
        });
    }

    /**
     * Password sign-in for a signer account. Returns empty when no signer account with a
     * password exists for the email, so the caller can answer with its generic
     * "Invalid email or password" without revealing which case applied.
     */
    public Optional<AuthService.LoginResult> passwordLogin(String email, String rawPassword,
                                                           String deviceInfo, String ip) {
        Optional<SignerAccount> accOpt = accountRepository.findByEmail(email);
        if (accOpt.isEmpty() || accOpt.get().getPasswordHash() == null) return Optional.empty();
        SignerAccount acc = accOpt.get();

        if (!acc.isActive()) throw new IllegalArgumentException("Account is disabled");

        LocalDateTime now = LocalDateTime.now();
        if (acc.getLockedUntil() != null && acc.getLockedUntil().isAfter(now)) {
            long mins = Duration.between(now, acc.getLockedUntil()).toMinutes() + 1;
            throw new IllegalArgumentException("Account locked due to too many failed attempts. Try again in "
                    + mins + " minute(s), or sign in with an email code.");
        }

        if (!passwordEncoder.matches(rawPassword, acc.getPasswordHash())) {
            registerFailedAttempt(acc);
            throw new IllegalArgumentException("Invalid email or password");
        }
        acc.setFailedLoginAttempts(0);
        acc.setLockedUntil(null);
        return Optional.of(issueSession(acc, deviceInfo, ip));
    }

    /** Opens a signer session for an already-verified account (OTP or password). */
    public AuthService.LoginResult issueSession(SignerAccount acc, String deviceInfo, String ip) {
        var sessionsCfg   = platformSettingsService.getSettings().getSecurity().getSessions();
        int maxConcurrent = sessionsCfg != null ? sessionsCfg.getMaxConcurrent()       : 3;
        int sessionHours  = sessionsCfg != null ? sessionsCfg.getSessionTimeoutHours() : 8;

        List<UserSession> active = sessionRepository.findByUserIdAndActiveTrueOrderByCreatedAtAsc(acc.getId());
        if (active.size() >= maxConcurrent) {
            active.stream().limit(active.size() - maxConcurrent + 1L).forEach(s -> {
                s.setActive(false);
                sessionRepository.save(s);
            });
        }

        String token      = jwtUtil.generateSignerToken(acc.getId(), acc.getEmail());
        String rawRefresh = jwtUtil.generateRefreshToken();

        sessionRepository.save(UserSession.builder()
                .userId(acc.getId())
                .principalType(PRINCIPAL_TYPE)
                .principalEmail(acc.getEmail())
                .userRole(PRINCIPAL_TYPE)
                .jti(jwtUtil.extractJti(token))
                .refreshTokenHash(jwtUtil.hashRefreshToken(rawRefresh))
                .deviceInfo(deviceInfo)
                .ipAddress(ip)
                .active(true)
                .emailVerified(true)   // OTP, or a password the signer set inside a verified session
                .expiresAt(LocalDateTime.now().plusHours(sessionHours))
                .lastUsedAt(LocalDateTime.now())
                .build());

        acc.setLastLoginAt(LocalDateTime.now());
        accountRepository.save(acc);

        log.info("Signer session issued for '{}'", acc.getEmail());
        return new AuthService.LoginResult(buildResponse(acc, token), rawRefresh);
    }

    /**
     * Rotates a signer session found by {@link AuthService#refresh}. The caller has
     * already enforced the absolute + idle timeouts on {@code session}.
     */
    public AuthService.LoginResult refresh(UserSession session) {
        SignerAccount acc = accountRepository.findById(session.getUserId())
                .filter(SignerAccount::isActive)
                .orElseThrow(() -> new IllegalArgumentException("Account is disabled"));

        String token      = jwtUtil.generateSignerToken(acc.getId(), acc.getEmail());
        String rawRefresh = jwtUtil.generateRefreshToken();
        session.setJti(jwtUtil.extractJti(token));
        session.setRefreshTokenHash(jwtUtil.hashRefreshToken(rawRefresh));
        session.setLastUsedAt(LocalDateTime.now());
        sessionRepository.save(session);
        return new AuthService.LoginResult(buildResponse(acc, token), rawRefresh);
    }

    public LoginResponse me(String accountId) {
        SignerAccount acc = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Signer account not found"));
        return buildResponse(acc, null);
    }

    /**
     * Sets or changes the signer's password. Changing an existing password requires the
     * current one; the first password can be set straight after an OTP sign-in.
     */
    public void setPassword(String accountId, String currentPassword, String newPassword) {
        SignerAccount acc = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Signer account not found"));
        if (acc.getPasswordHash() != null
                && (currentPassword == null || !passwordEncoder.matches(currentPassword, acc.getPasswordHash()))) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        passwordPolicyService.validate(newPassword, null);
        acc.setPasswordHash(passwordEncoder.encode(newPassword));
        acc.setPasswordChangedAt(LocalDateTime.now());
        acc.setFailedLoginAttempts(0);
        acc.setLockedUntil(null);
        accountRepository.save(acc);
        log.info("Signer '{}' {} a password", acc.getEmail(), currentPassword == null ? "set" : "changed");
    }

    private void registerFailedAttempt(SignerAccount acc) {
        var lockout     = platformSettingsService.getSettings().getSecurity().getLockout();
        int maxAttempts = lockout != null ? lockout.getMaxFailedAttempts() : 5;
        int lockMinutes = lockout != null ? lockout.getLockoutMinutes()    : 30;

        int attempts = acc.getFailedLoginAttempts() + 1;
        if (attempts >= maxAttempts) {
            acc.setFailedLoginAttempts(0);
            acc.setLockedUntil(LocalDateTime.now().plusMinutes(lockMinutes));
            log.warn("Signer account locked for '{}' after {} failed attempts", acc.getEmail(), maxAttempts);
        } else {
            acc.setFailedLoginAttempts(attempts);
        }
        accountRepository.save(acc);
    }

    private LoginResponse buildResponse(SignerAccount acc, String token) {
        return LoginResponse.builder()
                .token(token)
                .userId(acc.getId())
                .email(acc.getEmail())
                .firstName(acc.getName())
                .role(PRINCIPAL_TYPE)
                .accountType(PRINCIPAL_TYPE)
                .hasPassword(acc.getPasswordHash() != null)
                .features(List.of())
                .build();
    }
}
