package com.braify.feature.onboarding.service;

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
    private final com.braify.feature.platform.service.PlatformSettingsService platformSettingsService;

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

    private void sendSubmissionConfirmation(OnboardingRequest req) {
        int year = java.time.Year.now().getValue();
        Map<String, Object> vars = Map.of(
                "applicantName",    nz(req.getApplicantName()),
                "organizationName", nz(req.getOrganizationName()));
        var tpl = internalEmailTemplateService.find(InternalTemplateCodes.ONBOARDING_CONFIRMATION);
        String subject = tpl.map(EmailTemplate::getSubject).filter(this::notBlank)
                .orElse("We've received your Braify application — " + nz(req.getOrganizationName()));
        String body = tpl.map(EmailTemplate::getHtmlContent).filter(this::notBlank)
                .orElseGet(() -> buildConfirmationEmail(req.getApplicantName(), req.getOrganizationName(), year));
        trySend(req.getApplicantEmail(), subject, body, vars);
    }

    private void sendRejectionEmail(OnboardingRequest req) {
        int year = java.time.Year.now().getValue();
        String noteBlock = rejectionNoteBlock(req.getReviewNote());
        Map<String, Object> vars = Map.of(
                "applicantName",    nz(req.getApplicantName()),
                "organizationName", nz(req.getOrganizationName()),
                "reviewNoteBlock",  noteBlock);
        var tpl = internalEmailTemplateService.find(InternalTemplateCodes.ONBOARDING_REJECTION);
        String subject = tpl.map(EmailTemplate::getSubject).filter(this::notBlank)
                .orElse("Update on your Braify application");
        String body = tpl.map(EmailTemplate::getHtmlContent).filter(this::notBlank)
                .orElseGet(() -> buildRejectionEmail(req.getApplicantName(), req.getOrganizationName(), noteBlock, year));
        trySend(req.getApplicantEmail(), subject, body, vars);
    }

    private void sendInfoRequiredEmail(OnboardingRequest req) {
        int year = java.time.Year.now().getValue();
        String noteBlock = infoNoteBlock(req.getReviewNote());
        Map<String, Object> vars = Map.of(
                "applicantName",    nz(req.getApplicantName()),
                "organizationName", nz(req.getOrganizationName()),
                "reviewNoteBlock",  noteBlock);
        var tpl = internalEmailTemplateService.find(InternalTemplateCodes.ONBOARDING_INFO_REQUIRED);
        String subject = tpl.map(EmailTemplate::getSubject).filter(this::notBlank)
                .orElse("Additional information needed — Braify application");
        String body = tpl.map(EmailTemplate::getHtmlContent).filter(this::notBlank)
                .orElseGet(() -> buildInfoRequiredEmail(req.getApplicantName(), req.getOrganizationName(), noteBlock, year));
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

    // ── Email templates ───────────────────────────────────────────────────────

    private String buildConfirmationEmail(String applicantName, String organizationName, int year) {
        return """
            <!DOCTYPE html>
            <html><head><meta charset="UTF-8"></head>
            <body style="margin:0;padding:0;background:#f3f4f6;font-family:Inter,Arial,sans-serif;">
              <table width="100%%" cellpadding="0" cellspacing="0" style="padding:40px 0;">
                <tr><td align="center">
                  <table width="520" cellpadding="0" cellspacing="0"
                    style="background:#fff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,.07);">
                    <tr>
                      <td style="background:linear-gradient(135deg,#6366f1,#8b5cf6);padding:32px 40px;text-align:center;">
                        <h1 style="margin:0;color:#fff;font-size:22px;font-weight:700;">Braify</h1>
                        <p style="margin:6px 0 0;color:#c7d2fe;font-size:13px;">Application received</p>
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:36px 40px;">
                        <p style="margin:0 0 12px;font-size:15px;color:#374151;">Hi <strong>%s</strong>,</p>
                        <p style="margin:0 0 20px;font-size:14px;color:#6b7280;line-height:1.6;">
                          Thank you for applying to join <strong>Braify</strong>! We've received your application
                          for <strong>%s</strong> and our team will review it shortly.
                        </p>
                        <div style="background:#f9fafb;border-radius:12px;padding:20px;margin-bottom:24px;">
                          <p style="margin:0 0 8px;font-size:12px;font-weight:700;color:#6b7280;text-transform:uppercase;letter-spacing:.05em;">
                            What happens next?
                          </p>
                          <ul style="margin:0;padding:0 0 0 16px;font-size:13px;color:#4b5563;line-height:1.8;">
                            <li>Our team reviews your details (usually within 1–2 business days)</li>
                            <li>You'll receive an email with the outcome</li>
                            <li>If approved, you'll get a link to set your password and access Braify</li>
                          </ul>
                        </div>
                        <p style="font-size:12px;color:#9ca3af;margin:0;">
                          Questions? Reply to this email or contact our support team.
                        </p>
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:20px 40px;border-top:1px solid #f3f4f6;text-align:center;">
                        <p style="margin:0;font-size:11px;color:#d1d5db;">© %d Braify. All rights reserved.</p>
                      </td>
                    </tr>
                  </table>
                </td></tr>
              </table>
            </body></html>
            """.formatted(applicantName, organizationName, year);
    }

    private String buildRejectionEmail(String applicantName, String organizationName, String noteHtml, int year) {
        return """
            <!DOCTYPE html>
            <html><head><meta charset="UTF-8"></head>
            <body style="margin:0;padding:0;background:#f3f4f6;font-family:Inter,Arial,sans-serif;">
              <table width="100%%" cellpadding="0" cellspacing="0" style="padding:40px 0;">
                <tr><td align="center">
                  <table width="520" cellpadding="0" cellspacing="0"
                    style="background:#fff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,.07);">
                    <tr>
                      <td style="background:linear-gradient(135deg,#6366f1,#8b5cf6);padding:32px 40px;text-align:center;">
                        <h1 style="margin:0;color:#fff;font-size:22px;font-weight:700;">Braify</h1>
                        <p style="margin:6px 0 0;color:#c7d2fe;font-size:13px;">Application update</p>
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:36px 40px;">
                        <p style="margin:0 0 12px;font-size:15px;color:#374151;">Hi <strong>%s</strong>,</p>
                        <p style="margin:0 0 16px;font-size:14px;color:#6b7280;line-height:1.6;">
                          Thank you for your interest in Braify. After reviewing your application for
                          <strong>%s</strong>, we're unable to proceed at this time.
                        </p>
                        %s
                        <p style="font-size:13px;color:#6b7280;margin:0;">
                          If you believe this was an error or would like to reapply, please contact our team.
                        </p>
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:20px 40px;border-top:1px solid #f3f4f6;text-align:center;">
                        <p style="margin:0;font-size:11px;color:#d1d5db;">© %d Braify. All rights reserved.</p>
                      </td>
                    </tr>
                  </table>
                </td></tr>
              </table>
            </body></html>
            """.formatted(applicantName, organizationName, noteHtml, year);
    }

    private String buildInfoRequiredEmail(String applicantName, String organizationName, String noteHtml, int year) {
        return """
            <!DOCTYPE html>
            <html><head><meta charset="UTF-8"></head>
            <body style="margin:0;padding:0;background:#f3f4f6;font-family:Inter,Arial,sans-serif;">
              <table width="100%%" cellpadding="0" cellspacing="0" style="padding:40px 0;">
                <tr><td align="center">
                  <table width="520" cellpadding="0" cellspacing="0"
                    style="background:#fff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,.07);">
                    <tr>
                      <td style="background:linear-gradient(135deg,#6366f1,#8b5cf6);padding:32px 40px;text-align:center;">
                        <h1 style="margin:0;color:#fff;font-size:22px;font-weight:700;">Braify</h1>
                        <p style="margin:6px 0 0;color:#c7d2fe;font-size:13px;">Action required</p>
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:36px 40px;">
                        <p style="margin:0 0 12px;font-size:15px;color:#374151;">Hi <strong>%s</strong>,</p>
                        <p style="margin:0 0 16px;font-size:14px;color:#6b7280;line-height:1.6;">
                          We're reviewing your application for <strong>%s</strong> and need a bit more information
                          before we can proceed.
                        </p>
                        %s
                        <p style="font-size:13px;color:#6b7280;margin:0;">
                          Please reply to this email with the requested details and we'll continue reviewing your application.
                        </p>
                      </td>
                    </tr>
                    <tr>
                      <td style="padding:20px 40px;border-top:1px solid #f3f4f6;text-align:center;">
                        <p style="margin:0;font-size:11px;color:#d1d5db;">© %d Braify. All rights reserved.</p>
                      </td>
                    </tr>
                  </table>
                </td></tr>
              </table>
            </body></html>
            """.formatted(applicantName, organizationName, noteHtml, year);
    }

    /* ── INTERNAL template seeds ──────────────────────────────────────────── */
    @Override
    public List<InternalTemplateSeed> internalTemplateSeeds() {
        int year = java.time.Year.now().getValue();
        return List.of(
                new InternalTemplateSeed(
                        InternalTemplateCodes.ONBOARDING_CONFIRMATION,
                        "System — Onboarding: Application Received",
                        "We've received your Braify application — {{organizationName}}",
                        buildConfirmationEmail("{{applicantName}}", "{{organizationName}}", year),
                        List.of("applicantName", "organizationName")),
                new InternalTemplateSeed(
                        InternalTemplateCodes.ONBOARDING_REJECTION,
                        "System — Onboarding: Application Rejected",
                        "Update on your Braify application",
                        buildRejectionEmail("{{applicantName}}", "{{organizationName}}", "{{reviewNoteBlock}}", year),
                        List.of("applicantName", "organizationName", "reviewNoteBlock")),
                new InternalTemplateSeed(
                        InternalTemplateCodes.ONBOARDING_INFO_REQUIRED,
                        "System — Onboarding: Info Required",
                        "Additional information needed — Braify application",
                        buildInfoRequiredEmail("{{applicantName}}", "{{organizationName}}", "{{reviewNoteBlock}}", year),
                        List.of("applicantName", "organizationName", "reviewNoteBlock"))
        );
    }
}
