package com.braify.feature.esign.service;

import com.braify.config.infra.email.EmailDispatcher;
import com.braify.feature.esign.model.ESignDocument;
import com.braify.feature.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ESignEmailService {

    private final EmailDispatcher        emailDispatcher;
    private final OrganizationRepository orgRepo;

    @Value("${app.base-url:https://braify.com}")
    private String baseUrl;

    /**
     * Sends the signing invitation to the client with the signing link.
     * The "From" display name is the organization's name resolved from {@code doc.getOrgId()}.
     */
    public void sendSigningInvitation(ESignDocument doc, String signingToken) {
        String signingLink = baseUrl + "/sign/" + signingToken;
        String orgName     = resolveOrgName(doc.getOrgId());
        String html        = buildInvitationHtml(doc, signingLink, orgName);

        try {
            emailDispatcher.sendHtmlEmail(
                    doc.getClientEmail(),
                    "You have a document to sign — " + doc.getTitle(),
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
