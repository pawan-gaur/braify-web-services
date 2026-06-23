package com.braify.feature.auth.service;

import com.braify.config.EncryptionService;
import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.auth.dto.MfaSetupResponse;
import com.braify.feature.auth.dto.MfaStatusResponse;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.user.model.AppUser;
import com.braify.feature.user.repository.AppUserRepository;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.util.Utils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TOTP (RFC 6238) MFA: enrollment, verification, recovery codes, and the
 * org-policy resolution that decides whether a login needs a second factor.
 *
 * <p>Secrets are encrypted at rest via {@link EncryptionService}; recovery codes
 * are stored BCrypt-hashed. Disabling an org's MFA policy never touches these
 * fields (preserve-on-disable) — only a user self-disabling clears them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MfaService {

    private final AppUserRepository      userRepository;
    private final OrganizationRepository orgRepository;
    private final EncryptionService      encryptionService;
    private final PasswordEncoder        passwordEncoder;
    private final AuditLogService        auditLogService;
    private final com.braify.feature.platform.service.PlatformSettingsService platformSettingsService;

    private static final String ISSUER              = "Braify";
    private static final int    RECOVERY_CODE_COUNT = 10;
    private static final int    RECOVERY_CODE_LEN   = 10;
    // Excludes ambiguous chars (0/O, 1/I) for readability
    private static final char[] RECOVERY_ALPHABET   = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final CodeVerifier    codeVerifier    = new DefaultCodeVerifier(new DefaultCodeGenerator(), new SystemTimeProvider());
    private final QrGenerator     qrGenerator     = new ZxingPngQrGenerator();
    private final SecureRandom    secureRandom    = new SecureRandom();

    // ── Policy resolution ──────────────────────────────────────────────────────

    public enum MfaRequirement { NONE, CHALLENGE, MUST_SETUP }

    /** Effective org policy for a user (PLATFORM_ADMIN has no org → OPTIONAL). */
    public Organization.MfaPolicy policyFor(AppUser user) {
        if (user.getOrganizationId() == null) return Organization.MfaPolicy.OPTIONAL;
        Organization org = orgRepository.findById(user.getOrganizationId()).orElse(null);
        return (org != null && org.getMfaPolicy() != null)
                ? org.getMfaPolicy()
                : Organization.MfaPolicy.DISABLED;
    }

    /**
     * Effective MFA policy = platform-wide requirement OR the per-org policy.
     * If the platform requires MFA, it overrides the org (REQUIRED for everyone);
     * otherwise the org's own policy applies.
     */
    public Organization.MfaPolicy effectivePolicy(AppUser user) {
        var mfa = platformSettingsService.getSettings().getSecurity().getMfa();
        if (mfa != null && mfa.isRequired()) return Organization.MfaPolicy.REQUIRED;
        return policyFor(user);
    }

    /** What login should do for this user, given (effective policy × enrollment). */
    public MfaRequirement requirementAtLogin(AppUser user) {
        return switch (effectivePolicy(user)) {
            case DISABLED -> MfaRequirement.NONE;
            case OPTIONAL -> user.isMfaEnabled() ? MfaRequirement.CHALLENGE : MfaRequirement.NONE;
            case REQUIRED -> user.isMfaEnabled() ? MfaRequirement.CHALLENGE : MfaRequirement.MUST_SETUP;
        };
    }

    // ── Enrollment ──────────────────────────────────────────────────────────────

    /** Generate a pending secret + QR. Does NOT enable MFA yet. */
    public MfaSetupResponse setup(AppUser user) {
        String secret = secretGenerator.generate();
        user.setMfaPendingSecret(encryptionService.encrypt(secret));
        userRepository.save(user);

        QrData data = new QrData.Builder()
                .label(user.getEmail())
                .secret(secret)
                .issuer(ISSUER)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();
        String qrDataUri;
        try {
            qrDataUri = Utils.getDataUriForImage(qrGenerator.generate(data), qrGenerator.getImageMimeType());
        } catch (QrGenerationException e) {
            throw new RuntimeException("Failed to generate MFA QR code", e);
        }
        return MfaSetupResponse.builder()
                .secret(secret)
                .otpauthUri(data.getUri())
                .qrDataUri(qrDataUri)
                .build();
    }

    /** Verify the first code against the pending secret, enable MFA, return recovery codes (once). */
    public List<String> enable(AppUser user, String code) {
        if (user.getMfaPendingSecret() == null)
            throw new RuntimeException("Start MFA setup first");
        String pending = encryptionService.decrypt(user.getMfaPendingSecret());
        if (code == null || !codeVerifier.isValidCode(pending, code.trim()))
            throw new RuntimeException("Invalid verification code");

        user.setMfaSecret(user.getMfaPendingSecret());
        user.setMfaPendingSecret(null);
        user.setMfaEnabled(true);
        user.setMfaEnrolledAt(LocalDateTime.now());
        List<String> plain = generateRecoveryCodes();
        // Mutable list — recovery codes are removed one-by-one as they're consumed.
        user.setMfaRecoveryCodes(new ArrayList<>(plain.stream().map(passwordEncoder::encode).toList()));
        userRepository.save(user);

        audit(user, AuditLog.Action.MFA_ENABLED, null);
        return plain;
    }

    /** User self-disable — rejected when the org policy is REQUIRED. Clears the secret. */
    public void disable(AppUser user, String code) {
        if (effectivePolicy(user) == Organization.MfaPolicy.REQUIRED)
            throw new RuntimeException("MFA is required by policy and cannot be disabled");
        if (!user.isMfaEnabled())
            throw new RuntimeException("MFA is not enabled");
        if (!verifyCode(user, code))
            throw new RuntimeException("Invalid code");

        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        user.setMfaPendingSecret(null);
        user.setMfaRecoveryCodes(new ArrayList<>());
        user.setMfaEnrolledAt(null);
        userRepository.save(user);

        audit(user, AuditLog.Action.MFA_DISABLED, null);
    }

    public MfaStatusResponse status(AppUser user) {
        return MfaStatusResponse.builder()
                .orgPolicy(effectivePolicy(user).name())
                .enabled(user.isMfaEnabled())
                .enrolledAt(user.getMfaEnrolledAt())
                .recoveryCodesRemaining(user.getMfaRecoveryCodes() != null ? user.getMfaRecoveryCodes().size() : 0)
                .build();
    }

    /** Re-verify then issue a fresh set of recovery codes (old ones invalidated). */
    public List<String> regenerateRecoveryCodes(AppUser user, String code) {
        if (!user.isMfaEnabled() || !verifyCode(user, code))
            throw new RuntimeException("Invalid code");
        List<String> plain = generateRecoveryCodes();
        user.setMfaRecoveryCodes(new ArrayList<>(plain.stream().map(passwordEncoder::encode).toList()));
        userRepository.save(user);
        return plain;
    }

    // ── Verification ────────────────────────────────────────────────────────────

    /**
     * Verify a TOTP code first, then fall back to a one-time recovery code (which is
     * consumed on success and audited as MFA_RECOVERY_USED). Returns true on success.
     */
    public boolean verifyCode(AppUser user, String code) {
        if (code == null || code.isBlank()) return false;
        String c = code.trim();

        // 1) TOTP (6 digits)
        if (user.getMfaSecret() != null && c.matches("\\d{6}")) {
            String secret = encryptionService.decrypt(user.getMfaSecret());
            if (codeVerifier.isValidCode(secret, c)) return true;
        }

        // 2) Recovery code (consume on match)
        String norm = c.toUpperCase().replace("-", "").replace(" ", "");
        List<String> hashes = user.getMfaRecoveryCodes();
        if (hashes != null) {
            for (int i = 0; i < hashes.size(); i++) {
                if (passwordEncoder.matches(norm, hashes.get(i))) {
                    hashes.remove(i);
                    userRepository.save(user);
                    audit(user, AuditLog.Action.MFA_RECOVERY_USED,
                            Map.of("remaining", hashes.size()));
                    return true;
                }
            }
        }
        return false;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private List<String> generateRecoveryCodes() {
        List<String> codes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int n = 0; n < RECOVERY_CODE_COUNT; n++) {
            StringBuilder sb = new StringBuilder(RECOVERY_CODE_LEN);
            for (int i = 0; i < RECOVERY_CODE_LEN; i++)
                sb.append(RECOVERY_ALPHABET[secureRandom.nextInt(RECOVERY_ALPHABET.length)]);
            codes.add(sb.toString());
        }
        return codes;
    }

    private void audit(AppUser user, AuditLog.Action action, Map<String, Object> meta) {
        try {
            auditLogService.logByUser(user.getId(), user.getEmail(), action,
                    AuditLog.ResourceType.USER, 0, meta, user);
        } catch (Exception e) {
            log.warn("MFA audit log failed for action {} user {}: {}", action, user.getId(), e.getMessage());
        }
    }
}
