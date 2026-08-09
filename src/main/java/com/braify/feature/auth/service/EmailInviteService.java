package com.braify.feature.auth.service;

import com.braify.config.infra.email.EmailBrandVars;
import com.braify.config.infra.email.EmailDispatcher;
import com.braify.feature.emaillog.model.EmailLog;
import com.braify.feature.emaillog.service.EmailLogService;
import com.braify.feature.email.model.EmailTemplate;
import com.braify.feature.internaltemplate.InternalEmailTemplateService;
import com.braify.feature.internaltemplate.InternalTemplateCodes;
import com.braify.feature.internaltemplate.InternalTemplateProvider;
import com.braify.feature.internaltemplate.InternalTemplateSeed;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.user.model.AppUser;
import com.braify.feature.user.repository.AppUserRepository;
import com.braify.feature.auth.model.InvitationToken;
import com.braify.feature.auth.repository.InvitationTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generates and sends email invitations and password-reset links via Resend.
 * If the Resend API key is not configured the token is still created and the
 * invite URL is logged at INFO level (useful in development).
 *
 * <p>The email bodies are seeded as INTERNAL templates (see {@link #internalTemplateSeeds()})
 * and resolved by code at send time; the {@code buildInviteEmail}/{@code buildResetEmail}
 * text blocks below remain as the seed source <em>and</em> the fallback if the DB record is
 * missing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailInviteService implements InternalTemplateProvider {

    private final InvitationTokenRepository tokenRepository;
    private final EmailDispatcher           emailDispatcher;
    private final EmailLogService           emailLogService;
    private final InternalEmailTemplateService internalEmailTemplateService;
    private final OrganizationRepository    organizationRepository;
    private final AppUserRepository         appUserRepository;
    private final EmailBrandVars            emailBrandVars;

    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    private static final String PLATFORM_NAME = "Braify";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy");

    /* ── Public API ──────────────────────────────────────────────────────── */

    /**
     * Creates an INVITE token (7-day expiry) and sends a "set your password" email.
     * Safe to call even when Resend is not configured — falls back to console logging.
     *
     * @param user        target user whose invite is being sent
     * @param createdById userId of the admin / system that triggered the invite
     */
    public void sendInvite(AppUser user, String createdById) {
        // Invalidate any previous pending invites
        List<InvitationToken> old = tokenRepository
                .findByUserIdAndTypeAndUsedFalse(user.getId(), InvitationToken.TokenType.INVITE);
        old.forEach(t -> { t.setUsed(true); t.setUsedAt(LocalDateTime.now()); });
        tokenRepository.saveAll(old);

        String rawToken = UUID.randomUUID().toString();
        InvitationToken token = InvitationToken.builder()
                .userId(user.getId())
                .token(rawToken)
                .type(InvitationToken.TokenType.INVITE)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .used(false)
                .createdBy(createdById)
                .build();
        tokenRepository.save(token);

        String link = frontendUrl + "/accept-invite?token=" + rawToken;
        LocalDateTime expiresAt = token.getExpiresAt();

        // Organisation name + brand tokens (logo / accent / footer) for the invited user's org.
        String orgName = user.getOrganizationId() != null
                ? organizationRepository.findById(user.getOrganizationId())
                    .map(Organization::getName).orElse(PLATFORM_NAME)
                : PLATFORM_NAME;

        Map<String, Object> vars = new HashMap<>(emailBrandVars.forOrg(user.getOrganizationId(), orgName));
        vars.put("platformName",  PLATFORM_NAME);
        vars.put("signerName",    fullName(user));
        vars.put("signerEmail",   user.getEmail() != null ? user.getEmail() : "");
        vars.put("inviterName",   inviterName(createdById));
        vars.put("inviterEmail",  inviterEmail(createdById));
        vars.put("expiryDate",    expiresAt != null ? DATE_FMT.format(expiresAt) : "");
        vars.put("expiresIn",     "in 7 days");
        vars.put("link",          link);
        vars.put("firstName",     user.getFirstName() != null ? user.getFirstName() : ""); // legacy

        var tpl = internalEmailTemplateService.find(InternalTemplateCodes.INVITE_EMAIL);
        String subject = tpl.map(EmailTemplate::getSubject).filter(s -> s != null && !s.isBlank())
                .orElse("You're invited to join {{organizationName}} on {{platformName}}");
        String body = tpl.map(EmailTemplate::getHtmlContent).filter(h -> h != null && !h.isBlank())
                .orElseGet(this::buildInviteEmail);

        trySend(EmailLog.builder()
                        .orgId(user.getOrganizationId())
                        .category(EmailLog.Category.USER_INVITE)
                        .recipient(user.getEmail())
                        .senderName(orgName)
                        .relatedType(EmailLog.RelatedType.USER)
                        .relatedId(user.getId())
                        .createdBy(createdById)
                        .build(),
                subject, body, vars);
        // DEBUG only — invite tokens grant password-setting capability and must not
        // appear in production log aggregators (Splunk, CloudWatch, Datadog, etc.)
        log.debug("Invite link generated for {} (also sent by email): {}", user.getEmail(), link);
    }

    /**
     * Backward-compatible overload — createdById defaults to null.
     * Prefer {@link #sendInvite(AppUser, String)} when the admin's userId is available.
     */
    public void sendInvite(AppUser user) {
        sendInvite(user, null);
    }

    /**
     * Creates a PASSWORD_RESET token (1-hour expiry) and sends a reset email.
     * Self-service flow — createdBy is set to the requesting user's own ID.
     */
    public void sendPasswordReset(AppUser user) {
        // Invalidate previous pending resets
        List<InvitationToken> old = tokenRepository
                .findByUserIdAndTypeAndUsedFalse(user.getId(), InvitationToken.TokenType.PASSWORD_RESET);
        old.forEach(t -> { t.setUsed(true); t.setUsedAt(LocalDateTime.now()); });
        tokenRepository.saveAll(old);

        String rawToken = UUID.randomUUID().toString();
        InvitationToken token = InvitationToken.builder()
                .userId(user.getId())
                .token(rawToken)
                .type(InvitationToken.TokenType.PASSWORD_RESET)
                .expiresAt(LocalDateTime.now().plusHours(1))
                .used(false)
                .createdBy(user.getId())   // self-service — requester is the creator
                .build();
        tokenRepository.save(token);

        String link = frontendUrl + "/reset-password?token=" + rawToken;
        String orgName = user.getOrganizationId() != null
                ? organizationRepository.findById(user.getOrganizationId())
                    .map(Organization::getName).orElse(PLATFORM_NAME)
                : PLATFORM_NAME;

        Map<String, Object> vars = new HashMap<>(emailBrandVars.forOrg(user.getOrganizationId(), orgName));
        vars.put("platformName", PLATFORM_NAME);
        vars.put("firstName",    user.getFirstName() != null && !user.getFirstName().isBlank()
                                    ? user.getFirstName() : "there");
        vars.put("expiresIn",    "in 1 hour");
        vars.put("link",         link);

        var tpl = internalEmailTemplateService.find(InternalTemplateCodes.PASSWORD_RESET_EMAIL);
        String subject = tpl.map(EmailTemplate::getSubject).filter(s -> s != null && !s.isBlank())
                .orElse("Reset your password — {{platformName}}");
        String body = tpl.map(EmailTemplate::getHtmlContent).filter(h -> h != null && !h.isBlank())
                .orElseGet(this::buildResetEmail);

        trySend(EmailLog.builder()
                        .orgId(user.getOrganizationId())
                        .category(EmailLog.Category.PASSWORD_RESET)
                        .recipient(user.getEmail())
                        .senderName(orgName)
                        .relatedType(EmailLog.RelatedType.USER)
                        .relatedId(user.getId())
                        .createdBy(user.getId())
                        .build(),
                subject, body, vars);
        // DEBUG only — reset tokens grant unauthenticated password-change capability
        log.debug("Password-reset link generated for {} (also sent by email): {}", user.getEmail(), link);
    }

    /* ── Private helpers ─────────────────────────────────────────────────── */

    private void trySend(EmailLog logSpec, String subject, String htmlBody, Map<String, Object> vars) {
        String to = logSpec.getRecipient();
        // Subject is a template with {{tokens}} until the dispatcher renders it — store a readable copy.
        logSpec.setSubject(renderForLog(subject, vars));
        try {
            // EmailDispatcher substitutes {{tokens}} in subject + html using vars.
            emailLogService.recorded(logSpec,
                    () -> emailDispatcher.sendHtmlEmail(to, subject, htmlBody, vars));
            log.info("Email sent via Resend → {}", to);
        } catch (Exception e) {
            // Don't let email failure break the user-creation / reset flow
            log.warn("Could not send email to {} via Resend: {}", to, e.getMessage());
        }
    }

    /** Best-effort placeholder substitution so the logged subject is human-readable (not "{{platformName}}"). */
    private String renderForLog(String subject, Map<String, Object> vars) {
        if (subject == null) return null;
        String s = subject;
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            s = s.replace("{{" + e.getKey() + "}}", java.util.Objects.toString(e.getValue(), ""));
        }
        return s;
    }

    private String fullName(AppUser u) {
        String fn = u.getFirstName() != null ? u.getFirstName().trim() : "";
        String ln = u.getLastName()  != null ? u.getLastName().trim()  : "";
        String full = (fn + " " + ln).trim();
        return full.isEmpty() ? "there" : full;
    }

    private String inviterName(String createdById) {
        if (createdById == null) return "Your administrator";
        return appUserRepository.findById(createdById).map(u -> {
            String n = ((u.getFirstName() != null ? u.getFirstName() : "") + " "
                      + (u.getLastName()  != null ? u.getLastName()  : "")).trim();
            return !n.isEmpty() ? n : (u.getEmail() != null ? u.getEmail() : "Your administrator");
        }).orElse("Your administrator");
    }

    private String inviterEmail(String createdById) {
        if (createdById == null) return "";
        return appUserRepository.findById(createdById).map(AppUser::getEmail).orElse("");
    }

    /**
     * Platform invitation email (Template 04) — tokenised, email-client-safe (table layout).
     * Used both as the INTERNAL seed body and the fallback; {@code {{tokens}}} are substituted
     * by {@link EmailDispatcher} from the value map assembled in {@link #sendInvite}.
     */
    private String buildInviteEmail() {
        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head>
            <body style="margin:0;padding:0;background:#EEF2F6;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#EEF2F6;padding:32px 12px;"><tr><td align="center">
              <div style="width:600px;max-width:100%;text-align:left;background:#fff;border:1px solid #E2E8F0;border-radius:16px;overflow:hidden;box-shadow:0 12px 32px -12px rgba(15,23,42,0.12);">
                <div style="height:4px;background:{{accent}};"></div>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="border-bottom:1px solid #EEF2F6;"><tr>
                  <td style="padding:22px 32px;vertical-align:middle;">
                    <table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>
                      <td style="vertical-align:middle;padding-right:10px;">{{brandMark}}</td>
                      <td style="vertical-align:middle;font-size:15px;font-weight:700;color:#0F172A;">{{organizationName}}</td>
                    </tr></table>
                  </td>
                  <td align="right" style="padding:22px 32px;vertical-align:middle;white-space:nowrap;">
                    <span style="display:inline-block;width:6px;height:6px;border-radius:999px;background:#22C55E;vertical-align:middle;margin-right:6px;"></span><span style="font-size:10.5px;font-weight:700;letter-spacing:0.14em;color:#94A3B8;vertical-align:middle;">SECURE E-SIGN</span>
                  </td>
                </tr></table>
                <div style="padding:36px 32px 8px;">
                  <div style="font-size:11px;font-weight:700;letter-spacing:0.14em;color:{{accent}};margin-bottom:14px;">YOU'RE INVITED</div>
                  <h1 style="margin:0 0 14px;font-size:26px;line-height:1.25;font-weight:700;color:#0F172A;letter-spacing:-0.02em;">Join {{organizationName}} on {{platformName}}</h1>
                  <p style="margin:0 0 26px;font-size:15px;line-height:1.6;color:#475569;">Hi <strong style="color:#0F172A;">{{signerName}}</strong>,<br><strong style="color:#0F172A;">{{inviterName}}</strong> has invited you to join <strong style="color:#0F172A;">{{organizationName}}</strong> on {{platformName}}. Set your password to activate your account and start signing securely.</p>
                  <div style="border:1px solid #E2E8F0;border-radius:12px;background:#F8FAFC;overflow:hidden;">
                    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"><tr>
                      <td width="50%" style="padding:14px 20px;border-right:1px solid #E2E8F0;vertical-align:top;">
                        <div style="font-size:10.5px;font-weight:700;letter-spacing:0.1em;color:#94A3B8;margin-bottom:5px;">INVITED BY</div>
                        <div style="font-size:13.5px;font-weight:600;color:#0F172A;">{{inviterName}}</div>
                        <div style="font-size:12px;color:#64748B;margin-top:1px;">{{inviterEmail}}</div>
                      </td>
                      <td width="50%" style="padding:14px 20px;vertical-align:top;">
                        <div style="font-size:10.5px;font-weight:700;letter-spacing:0.1em;color:#94A3B8;margin-bottom:5px;">ORGANIZATION</div>
                        <div style="font-size:13.5px;font-weight:600;color:#0F172A;">{{organizationName}}</div>
                        <div style="font-size:12px;color:#64748B;margin-top:1px;">{{signerEmail}}</div>
                      </td>
                    </tr></table>
                    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="border-top:1px solid #E2E8F0;"><tr>
                      <td style="padding:14px 20px;vertical-align:middle;">
                        <div style="font-size:10.5px;font-weight:700;letter-spacing:0.1em;color:#94A3B8;margin-bottom:5px;">INVITATION EXPIRES</div>
                        <div style="font-size:13.5px;font-weight:600;color:#0F172A;">{{expiryDate}}</div>
                      </td>
                      <td align="right" style="padding:14px 20px;vertical-align:middle;white-space:nowrap;">
                        <span style="font-size:11px;font-weight:700;color:#B45309;background:#FEF3C7;border-radius:6px;padding:3px 9px;">{{expiresIn}}</span>
                      </td>
                    </tr></table>
                  </div>
                  <div style="text-align:center;margin:30px 0 8px;"><a href="{{link}}" style="display:inline-block;background:{{accent}};color:#fff;font-size:15px;font-weight:700;padding:15px 40px;border-radius:10px;text-decoration:none;">Accept Invitation</a></div>
                  <p style="margin:0 auto 30px;text-align:center;font-size:12.5px;line-height:1.5;color:#94A3B8;max-width:400px;">This invitation link is unique to you and expires {{expiresIn}}. Please don't forward this email.</p>
                </div>
                <div style="padding:22px 32px 28px;background:#F8FAFC;border-top:1px solid #EEF2F6;">
                  <p style="margin:0 0 12px;font-size:12px;line-height:1.6;color:#94A3B8;">If you weren't expecting this invitation, you can safely ignore this email — no account will be created and no further action is required.</p>
                  {{footerContact}}
                  <div style="margin-top:14px;font-size:11px;color:#CBD5E1;">Powered by <a href="https://braify.com/" style="color:#94A3B8;font-weight:700;text-decoration:none;">{{platformName}}</a> · 256-bit encrypted &amp; audit-logged</div>
                </div>
              </div>
            </td></tr></table></body></html>
            """;
    }

    /** Password-reset email — new design (tokenised, email-client-safe table layout). */
    private String buildResetEmail() {
        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head>
            <body style="margin:0;padding:0;background:#EEF2F6;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#EEF2F6;padding:32px 12px;"><tr><td align="center">
              <div style="width:600px;max-width:100%;text-align:left;background:#fff;border:1px solid #E2E8F0;border-radius:16px;overflow:hidden;box-shadow:0 12px 32px -12px rgba(15,23,42,0.12);">
                <div style="height:4px;background:{{accent}};"></div>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="border-bottom:1px solid #EEF2F6;"><tr>
                  <td style="padding:22px 32px;vertical-align:middle;">
                    <table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>
                      <td style="vertical-align:middle;padding-right:10px;">{{brandMark}}</td>
                      <td style="vertical-align:middle;font-size:15px;font-weight:700;color:#0F172A;">{{organizationName}}</td>
                    </tr></table>
                  </td>
                  <td align="right" style="padding:22px 32px;vertical-align:middle;white-space:nowrap;">
                    <span style="display:inline-block;width:6px;height:6px;border-radius:999px;background:#22C55E;vertical-align:middle;margin-right:6px;"></span><span style="font-size:10.5px;font-weight:700;letter-spacing:0.14em;color:#94A3B8;vertical-align:middle;">SECURE E-SIGN</span>
                  </td>
                </tr></table>
                <div style="padding:36px 32px 8px;">
                  <div style="font-size:11px;font-weight:700;letter-spacing:0.14em;color:{{accent}};margin-bottom:14px;">PASSWORD RESET</div>
                  <h1 style="margin:0 0 14px;font-size:26px;line-height:1.25;font-weight:700;color:#0F172A;letter-spacing:-0.02em;">Reset your password</h1>
                  <p style="margin:0 0 26px;font-size:15px;line-height:1.6;color:#475569;">Hi <strong style="color:#0F172A;">{{firstName}}</strong>, we received a request to reset the password for your <strong style="color:#0F172A;">{{organizationName}}</strong> account. Click the button below to choose a new one.</p>
                  <div style="border:1px solid #E2E8F0;border-radius:12px;background:#F8FAFC;padding:14px 20px;">
                    <div style="font-size:10.5px;font-weight:700;letter-spacing:0.1em;color:#94A3B8;margin-bottom:5px;">LINK EXPIRES</div>
                    <div style="font-size:13.5px;font-weight:600;color:#0F172A;">{{expiresIn}}</div>
                  </div>
                  <div style="text-align:center;margin:30px 0 8px;"><a href="{{link}}" style="display:inline-block;background:{{accent}};color:#fff;font-size:15px;font-weight:700;padding:15px 40px;border-radius:10px;text-decoration:none;">Reset Password</a></div>
                  <p style="margin:0 auto 30px;text-align:center;font-size:12.5px;line-height:1.5;color:#94A3B8;max-width:360px;">If you didn't request a password reset, you can safely ignore this email — your password won't change.</p>
                </div>
                <div style="padding:22px 32px 28px;background:#F8FAFC;border-top:1px solid #EEF2F6;">
                  {{footerContact}}
                  <div style="margin-top:14px;font-size:11px;color:#CBD5E1;">Powered by <a href="https://braify.com/" style="color:#94A3B8;font-weight:700;text-decoration:none;">{{platformName}}</a> · 256-bit encrypted &amp; audit-logged</div>
                </div>
              </div>
            </td></tr></table></body></html>
            """;
    }

    /* ── INTERNAL template seeds ─────────────────────────────────────────────
       Reuse the builders with {{token}} stand-ins to produce the seeded, tokenised
       HTML — guaranteeing the DB template matches the built-in output exactly. */
    @Override
    public List<InternalTemplateSeed> internalTemplateSeeds() {
        return List.of(
                new InternalTemplateSeed(
                        InternalTemplateCodes.INVITE_EMAIL,
                        "System — User Invitation",
                        "You're invited to join {{organizationName}} on {{platformName}}",
                        buildInviteEmail(),
                        List.of("organizationName", "brandMark", "accent", "platformName", "signerName",
                                "signerEmail", "inviterName", "inviterEmail", "expiryDate", "expiresIn",
                                "link", "footerContact")),
                new InternalTemplateSeed(
                        InternalTemplateCodes.PASSWORD_RESET_EMAIL,
                        "System — Password Reset",
                        "Reset your password — {{platformName}}",
                        buildResetEmail(),
                        List.of("organizationName", "brandMark", "accent", "platformName",
                                "firstName", "expiresIn", "link", "footerContact"))
        );
    }
}
