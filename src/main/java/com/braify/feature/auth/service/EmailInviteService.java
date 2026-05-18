package com.braify.feature.auth.service;

import com.braify.config.infra.email.EmailDispatcher;
import com.braify.feature.user.model.AppUser;
import com.braify.feature.auth.model.InvitationToken;
import com.braify.feature.auth.repository.InvitationTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Generates and sends email invitations and password-reset links via Resend.
 * If the Resend API key is not configured the token is still created and the
 * invite URL is logged at INFO level (useful in development).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailInviteService {

    private final InvitationTokenRepository tokenRepository;
    private final EmailDispatcher           emailDispatcher;

    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    /* ── Public API ──────────────────────────────────────────────────────── */

    /**
     * Creates an INVITE token (7-day expiry) and sends a "set your password" email.
     * Safe to call even when Resend is not configured — falls back to console logging.
     */
    public void sendInvite(AppUser user) {
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
                .build();
        tokenRepository.save(token);

        String link    = frontendUrl + "/accept-invite?token=" + rawToken;
        String subject = "You've been invited to PDF Builder";
        String body    = buildInviteEmail(user.getFirstName(), link);

        trySend(user.getEmail(), subject, body);
        log.info("=== INVITE LINK (also sent by email) ===\n{}\n=================", link);
    }

    /**
     * Creates a PASSWORD_RESET token (1-hour expiry) and sends a reset email.
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
                .build();
        tokenRepository.save(token);

        String link    = frontendUrl + "/reset-password?token=" + rawToken;
        String subject = "PDF Builder — Reset your password";
        String body    = buildResetEmail(user.getFirstName(), link);

        trySend(user.getEmail(), subject, body);
        log.info("=== PASSWORD RESET LINK (also sent by email) ===\n{}\n=================", link);
    }

    /* ── Private helpers ─────────────────────────────────────────────────── */

    private void trySend(String to, String subject, String htmlBody) {
        try {
            emailDispatcher.sendHtmlEmail(to, subject, htmlBody, Collections.emptyMap());
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
                        <h1 style="margin:0;color:#fff;font-size:22px;font-weight:700;">PDF Builder Studio</h1>
                        <p style="margin:6px 0 0;color:#c7d2fe;font-size:13px;">Template management platform</p>
                      </td>
                    </tr>
                    <!-- Body -->
                    <tr>
                      <td style="padding:36px 40px;">
                        <p style="margin:0 0 12px;font-size:15px;color:#374151;">Hi <strong>%s</strong>,</p>
                        <p style="margin:0 0 24px;font-size:14px;color:#6b7280;line-height:1.6;">
                          You've been invited to join <strong>PDF Builder Studio</strong>.
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
                        <p style="margin:0;font-size:11px;color:#d1d5db;">© 2025 PDF Builder Studio. All rights reserved.</p>
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
                        <h1 style="margin:0;color:#fff;font-size:22px;font-weight:700;">PDF Builder Studio</h1>
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
                        <p style="margin:0;font-size:11px;color:#d1d5db;">© 2025 PDF Builder Studio. All rights reserved.</p>
                      </td>
                    </tr>
                  </table>
                </td></tr>
              </table>
            </body>
            </html>
            """.formatted(firstName, link, link, link);
    }
}
