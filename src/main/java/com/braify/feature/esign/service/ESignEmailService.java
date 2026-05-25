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

import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ESignEmailService {

    private final EmailDispatcher          emailDispatcher;
    private final OrganizationRepository   orgRepo;
    private final EmailTemplateRepository  emailTemplateRepo;

    @Value("${app.base-url:https://braify.com}")
    private String baseUrl;

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
    public void sendSigningInvitation(ESignDocument doc, String signingToken) {
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
                html = applyESignPlaceholders(tpl.getHtmlContent(), doc, signingLink, orgName);
                log.debug("Using email template '{}' for doc {}", tpl.getName(), doc.getId());
            } else {
                log.warn("Email template '{}' not found for doc {} — using default",
                        doc.getEmailTemplateId(), doc.getId());
                subject = "You have a document to sign — " + doc.getTitle();
                html    = buildInvitationHtml(doc, signingLink, orgName);
            }
        } else {
            subject = "You have a document to sign — " + doc.getTitle();
            html    = buildInvitationHtml(doc, signingLink, orgName);
        }

        try {
            emailDispatcher.sendHtmlEmail(
                    doc.getClientEmail(),
                    subject,
                    html,
                    Map.of(
                            "clientName",    doc.getClientName(),
                            "documentTitle", doc.getTitle(),
                            "signingLink",   signingLink
                    ),
                    orgName
            );
            log.info("Signing invitation sent to {} for doc {}", doc.getClientEmail(), doc.getId());
        } catch (Exception e) {
            log.error("Failed to send signing invitation for doc {}: {}", doc.getId(), e.getMessage());
        }
    }

    /**
     * Substitutes e-sign–specific placeholders inside an email template's HTML content.
     * Handles both {@code {{placeholder}}} and {@code {{ placeholder }}} (with spaces).
     */
    private String applyESignPlaceholders(String html, ESignDocument doc,
                                          String signingLink, String orgName) {
        if (html == null) return "";
        return html
                .replaceAll("\\{\\{\\s*clientName\\s*\\}\\}",    escapeReplacement(doc.getClientName()))
                .replaceAll("\\{\\{\\s*documentTitle\\s*\\}\\}",  escapeReplacement(doc.getTitle()))
                .replaceAll("\\{\\{\\s*signingLink\\s*\\}\\}",    escapeReplacement(signingLink))
                .replaceAll("\\{\\{\\s*orgName\\s*\\}\\}",        escapeReplacement(orgName))
                // common aliases users may have typed in the template editor
                .replaceAll("\\{\\{\\s*client_name\\s*\\}\\}",    escapeReplacement(doc.getClientName()))
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

        // ── Email to client ──────────────────────────────────────────────────
        try {
            String clientHtml = buildCompletionHtml(doc, verifyLink, true, orgName);
            sendHtmlWithAttachment(doc.getClientEmail(), subject, clientHtml, signedPdfBytes, filename, orgName);
            log.info("Completion email sent to client {} for doc {}", doc.getClientEmail(), doc.getId());
        } catch (Exception e) {
            log.error("Failed to send completion email to client for doc {}: {}", doc.getId(), e.getMessage());
        }

        // ── Email to creator ─────────────────────────────────────────────────
        if (creatorEmail != null && !creatorEmail.isBlank()) {
            try {
                String creatorHtml = buildCompletionHtml(doc, verifyLink, false, orgName);
                sendHtmlWithAttachment(creatorEmail, subject, creatorHtml, signedPdfBytes, filename, orgName);
                log.info("Completion email sent to creator {} for doc {}", creatorEmail, doc.getId());
            } catch (Exception e) {
                log.error("Failed to send completion email to creator for doc {}: {}", doc.getId(), e.getMessage());
            }
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

    private String buildInvitationHtml(ESignDocument doc, String signingLink, String orgName) {
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
                """.formatted(orgName, doc.getClientName(), doc.getTitle(), signingLink);
    }

    private String buildCompletionHtml(ESignDocument doc, String verifyLink, boolean isClient, String orgName) {
        String greeting = isClient ? doc.getClientName() : "there";
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
