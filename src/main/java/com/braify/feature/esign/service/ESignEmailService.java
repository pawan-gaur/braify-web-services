package com.braify.feature.esign.service;

import com.braify.config.infra.email.EmailDispatcher;
import com.braify.feature.email.model.EmailTemplate;
import com.braify.feature.email.repository.EmailTemplateRepository;
import com.braify.feature.esign.model.ESignDocument;
import com.braify.feature.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ESignEmailService {

    private final EmailDispatcher          emailDispatcher;
    private final OrganizationRepository   orgRepo;
    private final EmailTemplateRepository  emailTemplateRepo;
    private final ESignTokenService        tokenService;

    /** Validity window for the read-only view links emailed to CC recipients. */
    private static final int VIEW_TOKEN_VALID_DAYS = 90;

    @Value("${app.base-url:https://braify.com}")
    private String baseUrl;

    /** Builds a read-only viewer link (view-only token) for CC recipients. */
    private String buildViewLink(ESignDocument doc) {
        return baseUrl + "/esign/view/" + tokenService.issueViewToken(doc.getId(), VIEW_TOKEN_VALID_DAYS);
    }

    /**
     * Sends the signing invitation to the client with the signing link.
     * <p>
     * If the document has an {@code emailTemplateId}, the org's saved email template is loaded
     * and its {@code htmlContent} is used after substituting the following placeholders:
     * <ul>
     *   <li>{@code {{clientName}}} → signer's name</li>
     *   <li>{@code {{documentTitle}}} → document title</li>
     *   <li>{@code {{signingLink}}} → one-time signing URL</li>
     *   <li>{@code {{orgName}}} → organisation display name</li>
     * </ul>
     * When no template is found the hardcoded default HTML is used as a safe fallback.
     * The "From" display name is always the organisation's name.
     */
    /** @return true if the invitation email was accepted by the provider, false if it failed. */
    public boolean sendSigningInvitation(ESignDocument doc,
                                      String recipientName,
                                      String recipientEmail,
                                      boolean includeCc,
                                      String signingToken) {
        String signingLink = baseUrl + "/sign/" + signingToken;
        String orgName     = resolveOrgName(doc.getOrgId());

        // ── Resolve email body: custom template or built-in fallback ────────
        String subject;
        String html;

        if (doc.getEmailTemplateId() != null && !doc.getEmailTemplateId().isBlank()) {
            Optional<EmailTemplate> tplOpt =
                    emailTemplateRepo.findByIdAndDeletedFalse(doc.getEmailTemplateId());

            if (tplOpt.isPresent()) {
                EmailTemplate tpl = tplOpt.get();
                subject = tpl.getSubject() != null && !tpl.getSubject().isBlank()
                        ? tpl.getSubject()
                        : "You have a document to sign — " + doc.getTitle();
                html = applyESignPlaceholders(tpl.getHtmlContent(), recipientName, doc, signingLink, orgName);
                log.debug("Using email template '{}' for doc {}", tpl.getName(), doc.getId());
            } else {
                log.warn("Email template '{}' not found for doc {} — using default",
                        doc.getEmailTemplateId(), doc.getId());
                subject = "You have a document to sign — " + doc.getTitle();
                html    = buildInvitationHtml(recipientName, doc, signingLink, orgName);
            }
        } else {
            subject = "You have a document to sign — " + doc.getTitle();
            html    = buildInvitationHtml(recipientName, doc, signingLink, orgName);
        }

        boolean sent = false;
        try {
            // IMPORTANT: the invitation (which contains the one-time signing link) goes ONLY to
            // the signatory — never CC it, or CC recipients could open the link and sign the
            // document themselves. CC recipients are notified separately, without the link.
            emailDispatcher.sendHtmlEmail(
                    recipientEmail,
                    subject,
                    html,
                    Map.of(
                            "clientName",    recipientName != null ? recipientName : "",
                            "documentTitle", doc.getTitle(),
                            "signingLink",   signingLink
                    ),
                    orgName
            );
            sent = true;
            log.info("Signing invitation sent to {} for doc {}", recipientEmail, doc.getId());
        } catch (Exception e) {
            log.error("Failed to send signing invitation to {} for doc {}: {}",
                    recipientEmail, doc.getId(), e.getMessage());
        }

        // Notify CC recipients that the document is out for signature — WITHOUT the signing link.
        if (includeCc && doc.getCcEmails() != null) {
            List<String> ccList = doc.getCcEmails().stream()
                    .filter(cc -> cc != null && !cc.isBlank())
                    .map(String::trim)
                    .toList();
            String ccSubject = "For your information: " + doc.getTitle() + " sent for signature";
            String ccHtml    = buildCcNotificationHtml(recipientName, doc, buildViewLink(doc), orgName);
            for (String cc : ccList) {
                try {
                    emailDispatcher.sendHtmlEmail(cc, ccSubject, ccHtml, Map.of(), orgName);
                    log.info("CC notification sent to {} for doc {}", cc, doc.getId());
                } catch (Exception e) {
                    log.error("Failed to send CC notification to {} for doc {}: {}", cc, doc.getId(), e.getMessage());
                }
            }
        }

        return sent;
    }

    /**
     * Informational email for CC ("keep in the loop") recipients when the document is sent.
     * Contains a VIEW-ONLY link (no signing, no download) — CC recipients can view the document
     * but never sign it.
     */
    private String buildCcNotificationHtml(String signerName, ESignDocument doc, String viewLink, String orgName) {
        String signer = (signerName != null && !signerName.isBlank()) ? signerName : "a recipient";
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family:Arial,sans-serif;max-width:600px;margin:40px auto;color:#333">
                  <div style="background:#7c3aed;padding:24px;border-radius:8px 8px 0 0;text-align:center">
                    <h1 style="color:#fff;margin:0;font-size:22px">%s e-Sign</h1>
                  </div>
                  <div style="background:#fff;padding:32px;border:1px solid #e5e7eb;border-top:none;border-radius:0 0 8px 8px">
                    <p>Hello,</p>
                    <p>You are being kept informed that the document below has been sent to
                       <strong>%s</strong> for electronic signature:</p>
                    <p style="font-size:18px;font-weight:bold;color:#7c3aed">%s</p>
                    <div style="text-align:center;margin:28px 0">
                      <a href="%s"
                         style="background:#7c3aed;color:#fff;padding:12px 28px;
                                border-radius:6px;text-decoration:none;font-weight:bold">
                        View Document
                      </a>
                    </div>
                    <p style="color:#6b7280">This is a view-only link — you cannot sign or edit the document.
                       No action is required from you.</p>
                    <p style="color:#6b7280;font-size:12px">Powered by %s e-Sign</p>
                  </div>
                </body>
                </html>
                """.formatted(orgName, signer, doc.getTitle(), viewLink, orgName);
    }

    /**
     * Notifies CC recipients that a document has been fully signed, with a VIEW-ONLY link to the
     * signed document (no attachment, no download).
     */
    private String buildCcCompletionHtml(ESignDocument doc, String viewLink, String orgName) {
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family:Arial,sans-serif;max-width:600px;margin:40px auto;color:#333">
                  <div style="background:#16a34a;padding:24px;border-radius:8px 8px 0 0;text-align:center">
                    <h1 style="color:#fff;margin:0;font-size:22px">Document Signed ✓</h1>
                  </div>
                  <div style="background:#fff;padding:32px;border:1px solid #e5e7eb;border-top:none;border-radius:0 0 8px 8px">
                    <p>Hello,</p>
                    <p>The document <strong>%s</strong> has been fully signed by all signatories.</p>
                    <div style="text-align:center;margin:28px 0">
                      <a href="%s"
                         style="background:#16a34a;color:#fff;padding:12px 28px;
                                border-radius:6px;text-decoration:none;font-weight:bold">
                        View Signed Document
                      </a>
                    </div>
                    <p style="color:#6b7280">This is a view-only link. No action is required from you.</p>
                    <p style="color:#6b7280;font-size:12px">Powered by %s e-Sign</p>
                  </div>
                </body>
                </html>
                """.formatted(doc.getTitle(), viewLink, orgName);
    }

    /**
     * Substitutes e-sign–specific placeholders inside an email template's HTML content.
     * Handles both {@code {{placeholder}}} and {@code {{ placeholder }}} (with spaces).
     */
    private String applyESignPlaceholders(String html, String recipientName, ESignDocument doc,
                                          String signingLink, String orgName) {
        if (html == null) return "";
        return html
                .replaceAll("\\{\\{\\s*clientName\\s*\\}\\}",    escapeReplacement(recipientName))
                .replaceAll("\\{\\{\\s*documentTitle\\s*\\}\\}",  escapeReplacement(doc.getTitle()))
                .replaceAll("\\{\\{\\s*signingLink\\s*\\}\\}",    escapeReplacement(signingLink))
                .replaceAll("\\{\\{\\s*orgName\\s*\\}\\}",        escapeReplacement(orgName))
                // common aliases users may have typed in the template editor
                .replaceAll("\\{\\{\\s*client_name\\s*\\}\\}",    escapeReplacement(recipientName))
                .replaceAll("\\{\\{\\s*document_title\\s*\\}\\}", escapeReplacement(doc.getTitle()))
                .replaceAll("\\{\\{\\s*signing_link\\s*\\}\\}",   escapeReplacement(signingLink))
                .replaceAll("\\{\\{\\s*org_name\\s*\\}\\}",       escapeReplacement(orgName));
    }

    /** Escapes special characters that {@link String#replaceAll} treats as replacement meta-chars. */
    private static String escapeReplacement(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("$", "\\$");
    }

    /**
     * Sends the completed signed PDF to both the creator and the client.
     * Uses sendHtmlEmail (inline HTML) + separate attachment call so no S3
     * template bucket is required.
     */
    @Async
    public void sendCompletionEmails(ESignDocument doc,
                                     String creatorEmail,
                                     String creatorName,
                                     byte[] signedPdfBytes) {
        String filename   = sanitizeFilename(doc.getTitle()) + "-signed.pdf";
        String verifyLink = baseUrl + "/verify/" + doc.getId();
        String subject    = "Signed document ready: " + doc.getTitle();
        String orgName    = resolveOrgName(doc.getOrgId());

        // Send to each recipient at most once (case-insensitive de-dupe across all groups).
        java.util.Set<String> sent = new java.util.HashSet<>();

        // ── Every signatory ──────────────────────────────────────────────────
        List<ESignDocument.Signatory> sigs = doc.getSignatories();
        if (sigs != null && !sigs.isEmpty()) {
            for (ESignDocument.Signatory s : sigs) {
                if (s.getEmail() == null || s.getEmail().isBlank()) continue;
                if (!sent.add(s.getEmail().trim().toLowerCase())) continue;
                sendCompletionTo(s.getEmail().trim(), s.getName(), subject, doc, verifyLink, orgName,
                        signedPdfBytes, filename);
            }
        } else if (doc.getClientEmail() != null && !doc.getClientEmail().isBlank()) {
            // legacy single-signer document
            if (sent.add(doc.getClientEmail().trim().toLowerCase()))
                sendCompletionTo(doc.getClientEmail().trim(), doc.getClientName(), subject, doc, verifyLink,
                        orgName, signedPdfBytes, filename);
        }

        // ── Creator ──────────────────────────────────────────────────────────
        if (creatorEmail != null && !creatorEmail.isBlank()
                && sent.add(creatorEmail.trim().toLowerCase())) {
            sendCompletionTo(creatorEmail.trim(), creatorName, subject, doc, verifyLink, orgName,
                    signedPdfBytes, filename);
        }

        // ── Copies to additional recipients ("send a copy of the signed document to") ──
        if (doc.getCompletionCcEmails() != null) {
            for (String raw : doc.getCompletionCcEmails()) {
                if (raw == null || raw.isBlank()) continue;
                String to = raw.trim();
                if (!sent.add(to.toLowerCase())) continue;
                sendCompletionTo(to, null, subject, doc, verifyLink, orgName, signedPdfBytes, filename);
            }
        }

        // ── Invitation-CC recipients: view-only "document signed" notice (no attachment) ──
        if (doc.getCcEmails() != null) {
            String ccSubject = "Signed: " + doc.getTitle();
            String ccHtml    = buildCcCompletionHtml(doc, buildViewLink(doc), orgName);
            for (String raw : doc.getCcEmails()) {
                if (raw == null || raw.isBlank()) continue;
                String to = raw.trim();
                if (!sent.add(to.toLowerCase())) continue;   // skip anyone already emailed a copy
                try {
                    emailDispatcher.sendHtmlEmail(to, ccSubject, ccHtml, Map.of(), orgName);
                    log.info("Completion view-notice sent to CC {} for doc {}", to, doc.getId());
                } catch (Exception e) {
                    log.error("Failed to send completion view-notice to {} for doc {}: {}", to, doc.getId(), e.getMessage());
                }
            }
        }
    }

    /** Sends one completion email (signed PDF attached); logs and swallows failures. */
    private void sendCompletionTo(String to, String greetingName, String subject, ESignDocument doc,
                                  String verifyLink, String orgName, byte[] signedPdfBytes, String filename) {
        try {
            String html = buildCompletionHtml(greetingName, doc, verifyLink, orgName);
            sendHtmlWithAttachment(to, subject, html, signedPdfBytes, filename, orgName);
            log.info("Completion email sent to {} for doc {}", to, doc.getId());
        } catch (Exception e) {
            log.error("Failed to send completion email to {} for doc {}: {}", to, doc.getId(), e.getMessage());
        }
    }

    /**
     * Sends an HTML email with a single PDF attachment.
     * Uses sendHtmlEmailWithAttachment so no S3 template bucket is needed.
     */
    private void sendHtmlWithAttachment(String to, String subject, String html,
                                        byte[] pdfBytes, String filename, String senderDisplayName) {
        emailDispatcher.sendHtmlEmailWithAttachment(
                to, subject, html, java.util.Map.of(), pdfBytes, filename, senderDisplayName);
    }

    // ── HTML builders ───────────────────────────────────────────────────────

    private String buildInvitationHtml(String recipientName, ESignDocument doc, String signingLink, String orgName) {
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family:Arial,sans-serif;max-width:600px;margin:40px auto;color:#333">
                  <div style="background:#7c3aed;padding:24px;border-radius:8px 8px 0 0;text-align:center">
                    <h1 style="color:#fff;margin:0;font-size:22px">%s e-Sign</h1>
                  </div>
                  <div style="background:#fff;padding:32px;border:1px solid #e5e7eb;border-top:none;border-radius:0 0 8px 8px">
                    <p>Hello <strong>%s</strong>,</p>
                    <p>You have been requested to sign a document:</p>
                    <p style="font-size:18px;font-weight:bold;color:#7c3aed">%s</p>
                    <p>Please click the button below to review and sign the document.</p>
                    <div style="text-align:center;margin:32px 0">
                      <a href="%s"
                         style="background:#7c3aed;color:#fff;padding:14px 32px;
                                border-radius:6px;text-decoration:none;font-weight:bold;font-size:16px">
                        Review &amp; Sign Document
                      </a>
                    </div>
                    <p style="color:#6b7280;font-size:12px">
                      This link is unique to you. Do not share it.<br>
                      If you did not expect this request, please ignore this email.
                    </p>
                  </div>
                </body>
                </html>
                """.formatted(orgName, recipientName, doc.getTitle(), signingLink);
    }

    private String buildCompletionHtml(String greetingName, ESignDocument doc, String verifyLink, String orgName) {
        String greeting = (greetingName != null && !greetingName.isBlank()) ? greetingName : "there";
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family:Arial,sans-serif;max-width:600px;margin:40px auto;color:#333">
                  <div style="background:#16a34a;padding:24px;border-radius:8px 8px 0 0;text-align:center">
                    <h1 style="color:#fff;margin:0;font-size:22px">Document Signed ✓</h1>
                  </div>
                  <div style="background:#fff;padding:32px;border:1px solid #e5e7eb;border-top:none;border-radius:0 0 8px 8px">
                    <p>Hello <strong>%s</strong>,</p>
                    <p>The document <strong>%s</strong> has been successfully signed.</p>
                    <p>The signed copy is attached to this email.</p>
                    <p>You can also verify the document's authenticity at any time:</p>
                    <div style="text-align:center;margin:24px 0">
                      <a href="%s"
                         style="background:#16a34a;color:#fff;padding:12px 28px;
                                border-radius:6px;text-decoration:none;font-weight:bold">
                        Verify Document
                      </a>
                    </div>
                    <p style="color:#6b7280;font-size:12px">Powered by %s e-Sign</p>
                  </div>
                </body>
                </html>
                """.formatted(greeting, doc.getTitle(), verifyLink, orgName);
    }

    /**
     * Looks up the organization name for the given org ID.
     * Falls back to {@code "Braify"} if the org is not found or the ID is blank,
     * so emails are never sent without a display name.
     */
    private String resolveOrgName(String orgId) {
        if (orgId == null || orgId.isBlank()) return "Braify";
        return orgRepo.findById(orgId)
                .map(org -> org.getName() != null && !org.getName().isBlank() ? org.getName() : "Braify")
                .orElse("Braify");
    }

    private String sanitizeFilename(String name) {
        return name == null ? "document" : name.replaceAll("[^a-zA-Z0-9._\\- ]", "").trim().replace(" ", "-");
    }
}
