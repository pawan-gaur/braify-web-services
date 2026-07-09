package com.braify.feature.auth.service;

import com.braify.config.infra.email.EmailDispatcher;
import com.braify.feature.email.model.EmailTemplate;
import com.braify.feature.internaltemplate.InternalEmailTemplateService;
import com.braify.feature.internaltemplate.InternalTemplateCodes;
import com.braify.feature.internaltemplate.InternalTemplateProvider;
import com.braify.feature.internaltemplate.InternalTemplateSeed;
import com.braify.feature.user.model.AppUser;
import com.braify.feature.auth.model.InvitationToken;
import com.braify.feature.auth.repository.InvitationTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
    private final InternalEmailTemplateService internalEmailTemplateService;

    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl;

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
        Map<String, Object> vars = Map.of(
                "firstName", user.getFirstName() != null ? user.getFirstName() : "",
                "link", link);

        var tpl = internalEmailTemplateService.find(InternalTemplateCodes.INVITE_EMAIL);
        String subject = tpl.map(EmailTemplate::getSubject).filter(s -> s != null && !s.isBlank())
                .orElse("You've been invited to Braify");
        String body = tpl.map(EmailTemplate::getHtmlContent).filter(h -> h != null && !h.isBlank())
                .orElseGet(() -> buildInviteEmail(user.getFirstName(), link));

        trySend(user.getEmail(), subject, body, vars);
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
        Map<String, Object> vars = Map.of(
                "firstName", user.getFirstName() != null ? user.getFirstName() : "",
                "link", link);

        var tpl = internalEmailTemplateService.find(InternalTemplateCodes.PASSWORD_RESET_EMAIL);
        String subject = tpl.map(EmailTemplate::getSubject).filter(s -> s != null && !s.isBlank())
                .orElse("Braify — Reset your password");
        String body = tpl.map(EmailTemplate::getHtmlContent).filter(h -> h != null && !h.isBlank())
                .orElseGet(() -> buildResetEmail(user.getFirstName(), link));

        trySend(user.getEmail(), subject, body, vars);
        // DEBUG only — reset tokens grant unauthenticated password-change capability
        log.debug("Password-reset link generated for {} (also sent by email): {}", user.getEmail(), link);
    }

    /* ── Private helpers ─────────────────────────────────────────────────── */

    private void trySend(String to, String subject, String htmlBody, Map<String, Object> vars) {
        try {
            // EmailDispatcher substitutes {{tokens}} in subject + html using vars.
            emailDispatcher.sendHtmlEmail(to, subject, htmlBody, vars);
            log.info("Email sent via Resend → {}", to);
        } catch (Exception e) {
            // Don't let email failure break the user-creation / reset flow
            log.warn("Could not send email to {} via Resend: {}", to, e.getMessage());
        }
    }

    private String buildInviteEmail(String firstName, String link) {
        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="margin:0;padding:0;background:#f3f4f6;font-family:Inter,Arial,sans-serif;">
              <table width="100%%" cellpadding="0" cellspacing="0" style="padding:40px 0;">
                <tr><td align="center">
                  <table width="520" cellpadding="0" cellspacing="0" style="background:#fff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,.07);">
                    <!-- Header -->
                    <tr>
                      <td style="background:linear-gradient(135deg,#6366f1,#8b5cf6);padding:32px 40px;text-align:center;">
                        <h1 style="margin:0;color:#fff;font-size:22px;font-weight:700;">Braify</h1>
                        <p style="margin:6px 0 0;color:#c7d2fe;font-size:13px;">Template management platform</p>
                      </td>
                    </tr>
                    <!-- Body -->
                    <tr>
                      <td style="padding:36px 40px;">
                        <p style="margin:0 0 12px;font-size:15px;color:#374151;">Hi <strong>%s</strong>,</p>
                        <p style="margin:0 0 24px;font-size:14px;color:#6b7280;line-height:1.6;">
                          You've been invited to join <strong>Braify</strong>.
                          Click the button below to set your password and activate your account.
                          This link expires in <strong>7 days</strong>.
                        </p>
                        <div style="text-align:center;margin:32px 0;">
                          <a href="%s" style="display:inline-block;padding:14px 36px;background:linear-gradient(135deg,#6366f1,#8b5cf6);color:#fff;text-decoration:none;border-radius:10px;font-weight:700;font-size:15px;">
                            Accept Invitation
                          </a>
                        </div>
                        <p style="font-size:12px;color:#9ca3af;margin:0;">
                          If you didn't expect this invitation, you can safely ignore this email.
                          <br>Or copy this link: <a href="%s" style="color:#6366f1;">%s</a>
                        </p>
                      </td>
                    </tr>
                    <!-- Footer -->
                    <tr>
                      <td style="padding:20px 40px;border-top:1px solid #f3f4f6;text-align:center;">
                        <p style="margin:0;font-size:11px;color:#d1d5db;">© 2025 Braify. All rights reserved.</p>
                      </td>
                    </tr>
                  </table>
                </td></tr>
              </table>
            </body>
            </html>
            """.formatted(firstName, link, link, link);
    }

    private String buildResetEmail(String firstName, String link) {
        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="margin:0;padding:0;background:#f3f4f6;font-family:Inter,Arial,sans-serif;">
              <table width="100%%" cellpadding="0" cellspacing="0" style="padding:40px 0;">
                <tr><td align="center">
                  <table width="520" cellpadding="0" cellspacing="0" style="background:#fff;border-radius:16px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,.07);">
                    <!-- Header -->
                    <tr>
                      <td style="background:linear-gradient(135deg,#6366f1,#8b5cf6);padding:32px 40px;text-align:center;">
                        <h1 style="margin:0;color:#fff;font-size:22px;font-weight:700;">Braify</h1>
                        <p style="margin:6px 0 0;color:#c7d2fe;font-size:13px;">Password Reset Request</p>
                      </td>
                    </tr>
                    <!-- Body -->
                    <tr>
                      <td style="padding:36px 40px;">
                        <p style="margin:0 0 12px;font-size:15px;color:#374151;">Hi <strong>%s</strong>,</p>
                        <p style="margin:0 0 24px;font-size:14px;color:#6b7280;line-height:1.6;">
                          We received a request to reset your password. Click the button below to choose a new one.
                          This link expires in <strong>1 hour</strong>.
                        </p>
                        <div style="text-align:center;margin:32px 0;">
                          <a href="%s" style="display:inline-block;padding:14px 36px;background:linear-gradient(135deg,#6366f1,#8b5cf6);color:#fff;text-decoration:none;border-radius:10px;font-weight:700;font-size:15px;">
                            Reset Password
                          </a>
                        </div>
                        <p style="font-size:12px;color:#9ca3af;margin:0;">
                          If you didn't request a password reset, you can safely ignore this email.
                          <br>Or copy this link: <a href="%s" style="color:#6366f1;">%s</a>
                        </p>
                      </td>
                    </tr>
                    <!-- Footer -->
                    <tr>
                      <td style="padding:20px 40px;border-top:1px solid #f3f4f6;text-align:center;">
                        <p style="margin:0;font-size:11px;color:#d1d5db;">© 2025 Braify. All rights reserved.</p>
                      </td>
                    </tr>
                  </table>
                </td></tr>
              </table>
            </body>
            </html>
            """.formatted(firstName, link, link, link);
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
                        "You've been invited to Braify",
                        buildInviteEmail("{{firstName}}", "{{link}}"),
                        List.of("firstName", "link")),
                new InternalTemplateSeed(
                        InternalTemplateCodes.PASSWORD_RESET_EMAIL,
                        "System — Password Reset",
                        "Braify — Reset your password",
                        buildResetEmail("{{firstName}}", "{{link}}"),
                        List.of("firstName", "link"))
        );
    }
}
