package com.braify.feature.onboarding.service;

import com.braify.config.infra.email.EmailBrandVars;
import com.braify.config.infra.email.EmailDispatcher;
import com.braify.feature.onboarding.dto.OnboardingReviewRequest;
import com.braify.feature.onboarding.dto.OnboardingSubmitRequest;
import com.braify.feature.onboarding.model.OnboardingRequest;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.user.model.AppUser;
import com.braify.feature.audit.model.AuditLog;
import com.braify.feature.audit.service.AuditLogService;
import com.braify.feature.auth.service.EmailInviteService;
import com.braify.feature.email.model.EmailTemplate;
import com.braify.feature.internaltemplate.InternalEmailTemplateService;
import com.braify.feature.internaltemplate.InternalTemplateCodes;
import com.braify.feature.internaltemplate.InternalTemplateProvider;
import com.braify.feature.internaltemplate.InternalTemplateSeed;
import com.braify.shared.Feature;
import com.braify.feature.user.repository.AppUserRepository;
import com.braify.feature.onboarding.repository.OnboardingRequestRepository;
import com.braify.feature.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardingRequestService implements InternalTemplateProvider {

    private final OnboardingRequestRepository requestRepository;
    private final OrganizationRepository      orgRepository;
    private final AppUserRepository           userRepository;
    private final EmailInviteService          emailInviteService;
    private final EmailDispatcher             emailDispatcher;
    private final PasswordEncoder             passwordEncoder;
    private final AuditLogService             auditLogService;
    private final InternalEmailTemplateService internalEmailTemplateService;
    private final EmailBrandVars              emailBrandVars;
    private final com.braify.feature.platform.service.PlatformSettingsService platformSettingsService;

    private static final String PLATFORM_NAME = "Braify";

    // ── Submit (public) ───────────────────────────────────────────────────────

    public OnboardingRequest submit(OnboardingSubmitRequest dto) {
        // Platform access policy: block public sign-up requests when disabled.
        if (!platformSettingsService.getSettings().getAccess().isAllowSelfSignup()) {
            throw new RuntimeException("Self-signup is currently disabled. Please contact an administrator.");
        }
        if (requestRepository.existsByApplicantEmail(dto.getApplicantEmail())) {
            throw new RuntimeException("A request with this email is already on file.");
        }

        OnboardingRequest req = OnboardingRequest.builder()
                .applicantName(dto.getApplicantName())
                .applicantEmail(dto.getApplicantEmail())
                .organizationName(dto.getOrganizationName())
                .description(dto.getDescription())
                .address(dto.getAddress())
                .state(dto.getState())
                .region(dto.getRegion())
                .country(dto.getCountry())
                .requestedFeatures(dto.getRequestedFeatures() != null ? dto.getRequestedFeatures() : List.of())
                .status(OnboardingRequest.Status.PENDING)
                .createdBy(dto.getApplicantEmail())   // public self-signup — record the applicant as creator
                .build();

        OnboardingRequest saved = requestRepository.save(req);
        log.info("New onboarding request from {} for org '{}'", saved.getApplicantEmail(), saved.getOrganizationName());

        sendSubmissionConfirmation(saved);
        return saved;
    }

    // ── Read (Platform Admin) ─────────────────────────────────────────────────

    public List<OnboardingRequest> findAll() {
        return requestRepository.findAllByOrderBySubmittedAtDesc();
    }

    public List<OnboardingRequest> findByStatus(OnboardingRequest.Status status) {
        return requestRepository.findByStatusOrderBySubmittedAtDesc(status);
    }

    public OnboardingRequest findById(String id) {
        return requestRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Onboarding request not found: " + id));
    }

    public long countPending() {
        return requestRepository.countByStatus(OnboardingRequest.Status.PENDING);
    }

    // ── Review (Platform Admin) ───────────────────────────────────────────────

    public OnboardingRequest review(String id, OnboardingReviewRequest review, String reviewerEmail) {
        OnboardingRequest req = findById(id);

        if (req.getStatus() == OnboardingRequest.Status.APPROVED) {
            throw new RuntimeException("This request has already been approved.");
        }

        OnboardingRequest.Status newStatus = switch (review.getAction().toUpperCase()) {
            case "APPROVE"       -> OnboardingRequest.Status.APPROVED;
            case "REJECT"        -> OnboardingRequest.Status.REJECTED;
            case "INFO_REQUIRED" -> OnboardingRequest.Status.INFO_REQUIRED;
            default -> throw new RuntimeException("Unknown action: " + review.getAction());
        };

        req.setStatus(newStatus);
        req.setReviewNote(review.getNote());
        req.setReviewedBy(reviewerEmail);
        req.setReviewedAt(LocalDateTime.now());

        if (newStatus == OnboardingRequest.Status.APPROVED) {
            approveRequest(req, review);
        } else if (newStatus == OnboardingRequest.Status.REJECTED) {
            sendRejectionEmail(req);
        } else {
            sendInfoRequiredEmail(req);
        }

        OnboardingRequest saved = requestRepository.save(req);

        // Audit the review action
        auditLogService.log(
                saved.getId(), saved.getOrganizationName(),
                AuditLog.Action.valueOf(
                        newStatus == OnboardingRequest.Status.APPROVED ? "CREATED" :
                        newStatus == OnboardingRequest.Status.REJECTED  ? "DELETED" : "UPDATED"),
                AuditLog.ResourceType.ORGANIZATION,
                0,
                review.getNote() != null ? java.util.Map.of("action", review.getAction(), "note", review.getNote()) : java.util.Map.of("action", review.getAction()),
                reviewerEmail,
                null);

        return saved;
    }

    // ── Approval logic ────────────────────────────────────────────────────────

    private void approveRequest(OnboardingRequest req, OnboardingReviewRequest review) {
        // 1. Determine features — PA may have adjusted them
        List<String> features = (review.getApprovedFeatures() != null && !review.getApprovedFeatures().isEmpty())
                ? Feature.sanitise(review.getApprovedFeatures())
                : Feature.sanitise(req.getRequestedFeatures());

        req.setApprovedFeatures(features);

        // 2. Create Organisation
        // Generate a short code from org name: uppercase, alphanumeric, first 8 chars
        String code = req.getOrganizationName()
                .replaceAll("[^A-Za-z0-9]", "")
                .toUpperCase();
        if (code.length() > 8) code = code.substring(0, 8);
        // Ensure uniqueness by appending a short suffix if needed
        String baseCode = code;
        int suffix = 1;
        while (orgRepository.existsByCode(code)) {
            code = baseCode + suffix++;
        }

        Organization org = Organization.builder()
                .name(req.getOrganizationName())
                .code(code)
                .description(req.getDescription())
                .features(features)
                .active(true)
                .deleted(false)
                .build();
        org = orgRepository.save(org);
        req.setCreatedOrganizationId(org.getId());

        // 3. Create the first user (ORG_ADMIN role, mustChangePassword = true)
        // Split applicant name into first/last
        String[] nameParts = req.getApplicantName().trim().split("\\s+", 2);
        String firstName = nameParts[0];
        String lastName  = nameParts.length > 1 ? nameParts[1] : "";

        // Check if user with this email already exists
        if (userRepository.findByEmail(req.getApplicantEmail()).isPresent()) {
            log.warn("User {} already exists — skipping user creation for onboarding approval", req.getApplicantEmail());
            return;
        }

        AppUser user = AppUser.builder()
                .email(req.getApplicantEmail())
                .password(passwordEncoder.encode(UUID.randomUUID().toString()))
                .firstName(firstName)
                .lastName(lastName)
                .role(AppUser.Role.ORG_ADMIN)
                .organizationId(org.getId())
                .active(true)
                .mustChangePassword(true)
                .build();
        user = userRepository.save(user);

        // 4. Send onboarding invite email (set-password link, 7-day expiry)
        emailInviteService.sendInvite(user);

        log.info("Approved onboarding for '{}': org={}, user={}", req.getApplicantEmail(), org.getId(), user.getId());
    }

    // ── Email helpers ─────────────────────────────────────────────────────────

    /** Platform-level brand tokens + applicant/org fields shared by all onboarding emails. */
    private Map<String, Object> onboardingVars(OnboardingRequest req) {
        // Applicants have no org yet, so branding is platform-level (Braify badge/accent).
        Map<String, Object> vars = new HashMap<>(emailBrandVars.forOrg(null, PLATFORM_NAME));
        vars.put("platformName",     PLATFORM_NAME);
        vars.put("organizationName", nz(req.getOrganizationName()));   // the org they applied for
        vars.put("applicantName",    notBlank(req.getApplicantName()) ? req.getApplicantName() : "there");
        return vars;
    }

    private void sendSubmissionConfirmation(OnboardingRequest req) {
        Map<String, Object> vars = onboardingVars(req);
        var tpl = internalEmailTemplateService.find(InternalTemplateCodes.ONBOARDING_CONFIRMATION);
        String subject = tpl.map(EmailTemplate::getSubject).filter(this::notBlank)
                .orElse("We've received your application — {{organizationName}}");
        String body = tpl.map(EmailTemplate::getHtmlContent).filter(this::notBlank)
                .orElseGet(this::buildConfirmationEmail);
        trySend(req.getApplicantEmail(), subject, body, vars);
    }

    private void sendRejectionEmail(OnboardingRequest req) {
        Map<String, Object> vars = onboardingVars(req);
        vars.put("reviewNoteBlock", rejectionNoteBlock(req.getReviewNote()));
        var tpl = internalEmailTemplateService.find(InternalTemplateCodes.ONBOARDING_REJECTION);
        String subject = tpl.map(EmailTemplate::getSubject).filter(this::notBlank)
                .orElse("Update on your {{platformName}} application");
        String body = tpl.map(EmailTemplate::getHtmlContent).filter(this::notBlank)
                .orElseGet(this::buildRejectionEmail);
        trySend(req.getApplicantEmail(), subject, body, vars);
    }

    private void sendInfoRequiredEmail(OnboardingRequest req) {
        Map<String, Object> vars = onboardingVars(req);
        vars.put("reviewNoteBlock", infoNoteBlock(req.getReviewNote()));
        var tpl = internalEmailTemplateService.find(InternalTemplateCodes.ONBOARDING_INFO_REQUIRED);
        String subject = tpl.map(EmailTemplate::getSubject).filter(this::notBlank)
                .orElse("Additional information needed — {{platformName}}");
        String body = tpl.map(EmailTemplate::getHtmlContent).filter(this::notBlank)
                .orElseGet(this::buildInfoRequiredEmail);
        trySend(req.getApplicantEmail(), subject, body, vars);
    }

    private void trySend(String to, String subject, String html, Map<String, Object> vars) {
        try {
            // EmailDispatcher substitutes {{tokens}} in subject + html using vars.
            emailDispatcher.sendHtmlEmail(to, subject, html, vars);
            log.info("Email sent → {}", to);
        } catch (Exception e) {
            log.warn("Could not send email to {}: {}", to, e.getMessage());
        }
    }

    private String nz(String s) { return s != null ? s : ""; }
    private boolean notBlank(String s) { return s != null && !s.isBlank(); }

    /** Red "Reason" callout for rejection emails; empty string when no note. */
    private String rejectionNoteBlock(String note) {
        return (note != null && !note.isBlank())
                ? "<div style='background:#fef2f2;border-left:3px solid #ef4444;padding:12px 16px;border-radius:8px;margin:16px 0;'>"
                  + "<p style='margin:0;font-size:13px;color:#991b1b;'><strong>Reason:</strong> " + note + "</p></div>"
                : "";
    }

    /** Amber "Information needed" callout for info-required emails; empty string when no note. */
    private String infoNoteBlock(String note) {
        return (note != null && !note.isBlank())
                ? "<div style='background:#fffbeb;border-left:3px solid #f59e0b;padding:12px 16px;border-radius:8px;margin:16px 0;'>"
                  + "<p style='margin:0;font-size:13px;color:#92400e;'><strong>Information needed:</strong> " + note + "</p></div>"
                : "";
    }

    // ── Email templates (new design; platform-branded, table layout) ──────────

    /** Brand bar: platform badge + name on the left, SECURE E-SIGN on the right. */
    private String brandBar() {
        return """
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="border-bottom:1px solid #EEF2F6;"><tr>
              <td style="padding:22px 32px;vertical-align:middle;">
                <table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>
                  <td style="vertical-align:middle;padding-right:10px;">{{brandMark}}</td>
                  <td style="vertical-align:middle;font-size:15px;font-weight:700;color:#0F172A;">{{platformName}}</td>
                </tr></table>
              </td>
              <td align="right" style="padding:22px 32px;vertical-align:middle;white-space:nowrap;">
                <span style="display:inline-block;width:6px;height:6px;border-radius:999px;background:#22C55E;vertical-align:middle;margin-right:6px;"></span><span style="font-size:10.5px;font-weight:700;letter-spacing:0.14em;color:#94A3B8;vertical-align:middle;">SECURE E-SIGN</span>
              </td>
            </tr></table>
            """;
    }

    private String footer() {
        return """
            <div style="padding:22px 32px 28px;background:#F8FAFC;border-top:1px solid #EEF2F6;">
              {{footerContact}}
              <div style="margin-top:14px;font-size:11px;color:#CBD5E1;">Powered by <a href="https://braify.com/" style="color:#94A3B8;font-weight:700;text-decoration:none;">{{platformName}}</a> · 256-bit encrypted &amp; audit-logged</div>
            </div>
            """;
    }

    private String buildConfirmationEmail() {
        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head>
            <body style="margin:0;padding:0;background:#EEF2F6;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#EEF2F6;padding:32px 12px;"><tr><td align="center">
              <div style="width:600px;max-width:100%;text-align:left;background:#fff;border:1px solid #E2E8F0;border-radius:16px;overflow:hidden;box-shadow:0 12px 32px -12px rgba(15,23,42,0.12);">
                <div style="height:4px;background:{{accent}};"></div>
                %BRANDBAR%
                <div style="padding:36px 32px 8px;">
                  <div style="font-size:11px;font-weight:700;letter-spacing:0.14em;color:{{accent}};margin-bottom:14px;">APPLICATION RECEIVED</div>
                  <h1 style="margin:0 0 14px;font-size:26px;line-height:1.25;font-weight:700;color:#0F172A;letter-spacing:-0.02em;">We've received your application</h1>
                  <p style="margin:0 0 22px;font-size:15px;line-height:1.6;color:#475569;">Hi <strong style="color:#0F172A;">{{applicantName}}</strong>, thanks for applying to join <strong style="color:#0F172A;">{{platformName}}</strong>. We've received your application for <strong style="color:#0F172A;">{{organizationName}}</strong> and our team will review it shortly.</p>
                  <div style="border:1px solid #E2E8F0;border-radius:12px;background:#F8FAFC;padding:18px 20px;">
                    <div style="font-size:10.5px;font-weight:700;letter-spacing:0.1em;color:#94A3B8;margin-bottom:10px;">WHAT HAPPENS NEXT</div>
                    <ul style="margin:0;padding:0 0 0 18px;font-size:13.5px;color:#475569;line-height:1.9;">
                      <li>Our team reviews your details (usually within 1–2 business days)</li>
                      <li>You'll receive an email with the outcome</li>
                      <li>If approved, you'll get a link to set your password and access {{platformName}}</li>
                    </ul>
                  </div>
                  <p style="margin:22px 0 30px;font-size:12.5px;line-height:1.5;color:#94A3B8;">Questions? Just reply to this email and our team will help.</p>
                </div>
                %FOOTER%
              </div>
            </td></tr></table></body></html>
            """.replace("%BRANDBAR%", brandBar()).replace("%FOOTER%", footer());
    }

    private String buildRejectionEmail() {
        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head>
            <body style="margin:0;padding:0;background:#EEF2F6;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#EEF2F6;padding:32px 12px;"><tr><td align="center">
              <div style="width:600px;max-width:100%;text-align:left;background:#fff;border:1px solid #E2E8F0;border-radius:16px;overflow:hidden;box-shadow:0 12px 32px -12px rgba(15,23,42,0.12);">
                <div style="height:4px;background:#64748B;"></div>
                %BRANDBAR%
                <div style="padding:36px 32px 8px;">
                  <div style="display:inline-block;font-size:11px;font-weight:700;letter-spacing:0.14em;color:#475569;background:#F1F5F9;border:1px solid #E2E8F0;border-radius:999px;padding:5px 12px;margin-bottom:16px;">APPLICATION UPDATE</div>
                  <h1 style="margin:0 0 14px;font-size:26px;line-height:1.25;font-weight:700;color:#0F172A;letter-spacing:-0.02em;">Update on your application</h1>
                  <p style="margin:0 0 16px;font-size:15px;line-height:1.6;color:#475569;">Hi <strong style="color:#0F172A;">{{applicantName}}</strong>, thank you for your interest in {{platformName}}. After reviewing your application for <strong style="color:#0F172A;">{{organizationName}}</strong>, we're unable to proceed at this time.</p>
                  {{reviewNoteBlock}}
                  <p style="margin:16px 0 30px;font-size:13.5px;line-height:1.6;color:#475569;">If you believe this was an error or would like to reapply, please contact our team.</p>
                </div>
                %FOOTER%
              </div>
            </td></tr></table></body></html>
            """.replace("%BRANDBAR%", brandBar()).replace("%FOOTER%", footer());
    }

    private String buildInfoRequiredEmail() {
        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head>
            <body style="margin:0;padding:0;background:#EEF2F6;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#EEF2F6;padding:32px 12px;"><tr><td align="center">
              <div style="width:600px;max-width:100%;text-align:left;background:#fff;border:1px solid #E2E8F0;border-radius:16px;overflow:hidden;box-shadow:0 12px 32px -12px rgba(15,23,42,0.12);">
                <div style="height:4px;background:#F59E0B;"></div>
                %BRANDBAR%
                <div style="padding:36px 32px 8px;">
                  <div style="display:inline-block;font-size:11px;font-weight:700;letter-spacing:0.14em;color:#B45309;background:#FEF3C7;border-radius:999px;padding:5px 12px;margin-bottom:16px;">ACTION REQUIRED</div>
                  <h1 style="margin:0 0 14px;font-size:26px;line-height:1.25;font-weight:700;color:#0F172A;letter-spacing:-0.02em;">Additional information needed</h1>
                  <p style="margin:0 0 16px;font-size:15px;line-height:1.6;color:#475569;">Hi <strong style="color:#0F172A;">{{applicantName}}</strong>, we're reviewing your application for <strong style="color:#0F172A;">{{organizationName}}</strong> and need a bit more information before we can proceed.</p>
                  {{reviewNoteBlock}}
                  <p style="margin:16px 0 30px;font-size:13.5px;line-height:1.6;color:#475569;">Please reply to this email with the requested details and we'll continue reviewing your application.</p>
                </div>
                %FOOTER%
              </div>
            </td></tr></table></body></html>
            """.replace("%BRANDBAR%", brandBar()).replace("%FOOTER%", footer());
    }

    /* ── INTERNAL template seeds ──────────────────────────────────────────── */
    @Override
    public List<InternalTemplateSeed> internalTemplateSeeds() {
        List<String> base     = List.of("applicantName", "organizationName", "platformName", "brandMark", "accent", "footerContact");
        List<String> withNote = List.of("applicantName", "organizationName", "platformName", "brandMark", "accent", "footerContact", "reviewNoteBlock");
        return List.of(
                new InternalTemplateSeed(
                        InternalTemplateCodes.ONBOARDING_CONFIRMATION,
                        "System — Onboarding: Application Received",
                        "We've received your application — {{organizationName}}",
                        buildConfirmationEmail(),
                        base),
                new InternalTemplateSeed(
                        InternalTemplateCodes.ONBOARDING_REJECTION,
                        "System — Onboarding: Application Rejected",
                        "Update on your {{platformName}} application",
                        buildRejectionEmail(),
                        withNote),
                new InternalTemplateSeed(
                        InternalTemplateCodes.ONBOARDING_INFO_REQUIRED,
                        "System — Onboarding: Info Required",
                        "Additional information needed — {{platformName}}",
                        buildInfoRequiredEmail(),
                        withNote)
        );
    }
}
