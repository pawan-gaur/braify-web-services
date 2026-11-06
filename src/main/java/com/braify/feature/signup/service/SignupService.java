package com.braify.feature.signup.service;

import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.auth.service.AuthService;
import com.braify.feature.auth.service.PasswordPolicyService;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.service.OrgProvisioningService;
import com.braify.feature.signer.service.EmailOtpService;
import com.braify.feature.signer.service.SignerEmails;
import com.braify.feature.signup.dto.SignupRequest;
import com.braify.feature.user.model.AppUser;
import com.braify.feature.user.repository.AppUserRepository;
import com.braify.shared.Feature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Instant self-serve sign-up: email code → new organisation on the FREE plan with every
 * feature enabled → the applicant as its ORG_ADMIN → signed in.
 *
 * <p>The approval-based onboarding request flow still exists (used for "Contact sales");
 * both create orgs through {@link OrgProvisioningService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignupService {

    private final AppUserRepository      userRepository;
    private final OrgProvisioningService orgProvisioningService;
    private final PasswordPolicyService  passwordPolicyService;
    private final EmailOtpService        emailOtpService;
    private final AuthService            authService;
    private final AuditLogService        auditLogService;
    private final com.braify.feature.billing.service.BillingService billingService;
    private final com.braify.feature.organization.repository.OrganizationRepository orgRepository;

    /** Kill switch for public sign-up (orgs can still be onboarded via approval). */
    @Value("${app.signup.enabled:true}")
    private boolean enabled;

    /** Step 1 — validate the form and email a verification code. */
    public void start(SignupRequest req, String userAgent, String ip) {
        String email = validate(req);
        emailOtpService.issue(email, fullName(req), userAgent, ip);
        log.info("Sign-up code requested for '{}' (org '{}')", email, req.getOrganizationName().trim());
    }

    /** Step 2 — verify the code, create the organisation + admin, and open their session. */
    public AuthService.LoginResult complete(SignupRequest req, String ip) {
        String email = validate(req);   // before consuming the code, so a typo doesn't burn it
        if (req.getCode() == null || req.getCode().isBlank()) {
            throw new IllegalArgumentException("Enter the code we emailed you.");
        }
        emailOtpService.verify(email, req.getCode());

        // Re-check: the address could have been registered between the two steps.
        if (!userRepository.findByEmailIgnoreCase(email).isEmpty()) {
            throw new IllegalStateException("An account with this email already exists. Sign in instead.");
        }

        Organization org = orgProvisioningService.createOrganization(
                req.getOrganizationName().trim(), null, Feature.allKeys());

        AppUser user = AppUser.builder()
                .email(email)
                .firstName(req.getFirstName().trim())
                .lastName(req.getLastName() != null ? req.getLastName().trim() : "")
                .role(AppUser.Role.ORG_ADMIN)
                .organizationId(org.getId())
                .active(true)
                .mustChangePassword(false)
                .passwordMailboxVerified(true)   // chosen by the user in an email-verified flow
                .build();
        try {
            passwordPolicyService.applyNewPassword(user, req.getPassword());
            user = userRepository.save(user);
        } catch (RuntimeException e) {
            // Don't leave an empty organisation behind (e.g. a duplicate-email race on save).
            orgRepository.deleteById(org.getId());
            log.warn("Sign-up for '{}' failed after creating org {} — org removed: {}", email, org.getId(), e.getMessage());
            throw e;
        }

        try {
            billingService.startTrial(org);   // 14-day Pro trial (no card); Free afterwards unless subscribed
        } catch (RuntimeException e) {
            log.error("Could not start trial for org {}: {}", org.getId(), e.getMessage());
        }

        auditLogService.log(org.getId(), org.getName(),
                AuditLog.Action.CREATED, AuditLog.ResourceType.ORGANIZATION,
                0, java.util.Map.of("source", "SELF_SERVE_SIGNUP"), email, org.getId());
        auditLogService.log(user.getId(), user.getEmail(),
                AuditLog.Action.CREATED, AuditLog.ResourceType.USER,
                0, java.util.Map.of("source", "SELF_SERVE_SIGNUP"), email, org.getId());
        log.info("Self-serve sign-up: org={} ('{}') admin={}", org.getId(), org.getName(), email);

        return authService.openSessionAfterSignup(user, req.getDeviceInfo(), ip);
    }

    /** Shared checks for both steps; returns the normalized email. */
    private String validate(SignupRequest req) {
        if (!enabled) {
            throw new IllegalStateException("Self-serve sign-up is currently disabled. Please contact sales.");
        }
        String email = SignerEmails.normalize(req.getEmail());
        if (email == null) throw new IllegalArgumentException("Email is required");
        if (req.getFirstName() == null || req.getFirstName().isBlank()) {
            throw new IllegalArgumentException("Your first name is required");
        }
        if (req.getOrganizationName() == null || req.getOrganizationName().trim().length() < 2) {
            throw new IllegalArgumentException("Organisation name must be at least 2 characters");
        }
        if (!userRepository.findByEmailIgnoreCase(email).isEmpty()) {
            throw new IllegalStateException("An account with this email already exists. Sign in instead.");
        }
        passwordPolicyService.validate(req.getPassword(), null);
        return email;
    }

    private static String fullName(SignupRequest req) {
        return ((req.getFirstName() != null ? req.getFirstName().trim() : "") + " "
                + (req.getLastName() != null ? req.getLastName().trim() : "")).trim();
    }
}
