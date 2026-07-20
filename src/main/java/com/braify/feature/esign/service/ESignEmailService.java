package com.braify.feature.esign.service;

import com.braify.config.infra.email.EmailDispatcher;
import com.braify.feature.email.model.EmailTemplate;
import com.braify.feature.email.repository.EmailTemplateRepository;
import com.braify.feature.esign.model.ESignDocument;
import com.braify.feature.internaltemplate.InternalTemplateCodes;
import com.braify.feature.internaltemplate.InternalTemplateProvider;
import com.braify.feature.internaltemplate.InternalTemplateSeed;
import com.braify.feature.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class ESignEmailService implements InternalTemplateProvider {

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

        // ── Resolve email body ──────────────────────────────────────────────
        // Priority: 1) org-chosen custom template (EXTERNAL), 2) platform INTERNAL
        // system template (by code), 3) built-in HTML fallback.
        String subject;
        String html;

        Optional<EmailTemplate> customTpl = Optional.empty();
        if (doc.getEmailTemplateId() != null && !doc.getEmailTemplateId().isBlank()) {
            customTpl = emailTemplateRepo.findByIdAndDeletedFalse(doc.getEmailTemplateId());
            if (customTpl.isEmpty())
                log.warn("Email template '{}' not found for doc {} — using system template",
                        doc.getEmailTemplateId(), doc.getId());
        }

        if (customTpl.isPresent()) {
            EmailTemplate tpl = customTpl.get();
            subject = notBlank(tpl.getSubject()) ? tpl.getSubject() : "You have a document to sign — " + doc.getTitle();
            html    = applyESignPlaceholders(tpl.getHtmlContent(), recipientName, doc, signingLink, orgName);
            log.debug("Using custom email template '{}' for doc {}", tpl.getName(), doc.getId());
        } else {
            ResolvedEmail r = resolveInternal(
                    InternalTemplateCodes.ESIGN_SIGNING_INVITATION,
                    "You have a document to sign — {{documentName}}",
                    this::buildInvitationHtml);
            subject = r.subject();
            html    = r.html();
        }

        Map<String, Object> vars = new java.util.HashMap<>(brandVars(doc.getOrgId(), orgName));
        vars.put("signerName",    recipientName != null ? recipientName : "");
        vars.put("signerEmail",   recipientEmail != null ? recipientEmail : "");
        vars.put("documentName",  doc.getTitle() != null ? doc.getTitle() : "");
        vars.put("expiryDate",    fmt(doc.getTokenExpiresAt(), EXPIRY_FMT));
        vars.put("expiresIn",     expiresInPhrase(doc.getTokenExpiresAt()));
        vars.put("signingLink",   signingLink);
        // legacy aliases for any org-custom template still using the old tokens
        vars.put("clientName",    recipientName != null ? recipientName : "");
        vars.put("documentTitle", doc.getTitle() != null ? doc.getTitle() : "");

        boolean sent = false;
        try {
            // IMPORTANT: the invitation (which contains the one-time signing link) goes ONLY to
            // the signatory — never CC it, or CC recipients could open the link and sign the
            // document themselves. CC recipients are notified separately, without the link.
            emailDispatcher.sendHtmlEmail(
                    recipientEmail,
                    subject,
                    html,
                    vars,
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
            String viewLink = buildViewLink(doc);
            Map<String, Object> ccVars = new java.util.HashMap<>(brandVars(doc.getOrgId(), orgName));
            ccVars.put("ccName",       "there");   // CC recipients are stored as emails only — generic greeting
            ccVars.put("signerName",   notBlank(recipientName) ? recipientName : "a recipient");
            ccVars.put("signerEmail",  recipientEmail != null ? recipientEmail : "");
            ccVars.put("documentName", doc.getTitle() != null ? doc.getTitle() : "");
            ccVars.put("sentOn",       fmt(doc.getSentAt() != null ? doc.getSentAt() : java.time.LocalDateTime.now(), SIGNED_FMT));
            ccVars.put("viewLink",     viewLink);
            ResolvedEmail ccR = resolveInternal(
                    InternalTemplateCodes.ESIGN_CC_NOTIFICATION,
                    "For your information: {{documentName}} sent for signature",
                    this::buildCcNotificationHtml);
            for (String cc : ccList) {
                try {
                    emailDispatcher.sendHtmlEmail(cc, ccR.subject(), ccR.html(), ccVars, orgName);
                    log.info("CC notification sent to {} for doc {}", cc, doc.getId());
                } catch (Exception e) {
                    log.error("Failed to send CC notification to {} for doc {}: {}", cc, doc.getId(), e.getMessage());
                }
            }
        }

        return sent;
    }

    /** CC ("keep in the loop") notice when a document is sent — view-only, Template-01 design. */
    private String buildCcNotificationHtml() { return ESignEmailTemplates.CC_NOTIFICATION; }

    /** CC notice when a document is fully signed — view-only, Template-02 design. */
    private String buildCcCompletionHtml() { return ESignEmailTemplates.CC_COMPLETION; }

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
     * Sends the completed signed PDF to every signatory, the creator, and the
     * "send a copy" recipients, plus a view-only "document signed" notice to the
     * invitation-CC recipients. Uses sendHtmlEmail (inline HTML) + separate
     * attachment call so no S3 template bucket is required.
     *
     * <p>Runs synchronously and returns one {@link ESignDocument.CompletionNotification}
     * per recipient (with per-recipient delivery status) so the caller can persist an
     * auditable record of who was notified. Callers invoke this from an async context.</p>
     */
    public List<ESignDocument.CompletionNotification> sendCompletionEmails(ESignDocument doc,
                                                                           String creatorEmail,
                                                                           String creatorName,
                                                                           byte[] signedPdfBytes) {
        String filename   = sanitizeFilename(doc.getTitle()) + "-signed.pdf";
        String verifyLink = baseUrl + "/verify/" + doc.getId();
        String subject    = "Signed document ready: " + doc.getTitle();
        String orgName    = resolveOrgName(doc.getOrgId());

        List<ESignDocument.CompletionNotification> notifications = new java.util.ArrayList<>();

        // Send to each recipient at most once (case-insensitive de-dupe across all groups).
        java.util.Set<String> sent = new java.util.HashSet<>();

        // ── Every signatory ──────────────────────────────────────────────────
        List<ESignDocument.Signatory> sigs = doc.getSignatories();
        if (sigs != null && !sigs.isEmpty()) {
            for (ESignDocument.Signatory s : sigs) {
                if (s.getEmail() == null || s.getEmail().isBlank()) continue;
                if (!sent.add(s.getEmail().trim().toLowerCase())) continue;
                boolean ok = sendCompletionTo(s.getEmail().trim(), s.getName(), subject, doc, verifyLink, orgName,
                        signedPdfBytes, filename);
                notifications.add(note(s.getEmail().trim(), s.getName(),
                        ESignDocument.NotificationRole.SIGNATORY, ok, true));
            }
        } else if (doc.getClientEmail() != null && !doc.getClientEmail().isBlank()) {
            // legacy single-signer document
            if (sent.add(doc.getClientEmail().trim().toLowerCase())) {
                boolean ok = sendCompletionTo(doc.getClientEmail().trim(), doc.getClientName(), subject, doc, verifyLink,
                        orgName, signedPdfBytes, filename);
                notifications.add(note(doc.getClientEmail().trim(), doc.getClientName(),
                        ESignDocument.NotificationRole.SIGNATORY, ok, true));
            }
        }

        // ── Creator ──────────────────────────────────────────────────────────
        if (creatorEmail != null && !creatorEmail.isBlank()
                && sent.add(creatorEmail.trim().toLowerCase())) {
            boolean ok = sendCompletionTo(creatorEmail.trim(), creatorName, subject, doc, verifyLink, orgName,
                    signedPdfBytes, filename);
            notifications.add(note(creatorEmail.trim(), creatorName,
                    ESignDocument.NotificationRole.CREATOR, ok, true));
        }

        // ── Copies to additional recipients ("send a copy of the signed document to") ──
        if (doc.getCompletionCcEmails() != null) {
            for (String raw : doc.getCompletionCcEmails()) {
                if (raw == null || raw.isBlank()) continue;
                String to = raw.trim();
                if (!sent.add(to.toLowerCase())) continue;
                boolean ok = sendCompletionTo(to, null, subject, doc, verifyLink, orgName, signedPdfBytes, filename);
                notifications.add(note(to, null, ESignDocument.NotificationRole.COMPLETION_CC, ok, true));
            }
        }

        // ── Invitation-CC recipients: view-only "document signed" notice (no attachment) ──
        if (doc.getCcEmails() != null) {
            String viewLink = buildViewLink(doc);
            Map<String, Object> ccVars = new java.util.HashMap<>(brandVars(doc.getOrgId(), orgName));
            ccVars.put("documentName", doc.getTitle() != null ? doc.getTitle() : "");
            ccVars.put("signedOn",     fmt(doc.getCompletedAt(), SIGNED_FMT));
            ccVars.put("viewLink",     viewLink);
            ResolvedEmail ccR = resolveInternal(
                    InternalTemplateCodes.ESIGN_CC_COMPLETION,
                    "Signed: {{documentName}}",
                    this::buildCcCompletionHtml);
            for (String raw : doc.getCcEmails()) {
                if (raw == null || raw.isBlank()) continue;
                String to = raw.trim();
                if (!sent.add(to.toLowerCase())) continue;   // skip anyone already emailed a copy
                boolean ok;
                try {
                    emailDispatcher.sendHtmlEmail(to, ccR.subject(), ccR.html(), ccVars, orgName);
                    log.info("Completion view-notice sent to CC {} for doc {}", to, doc.getId());
                    ok = true;
                } catch (Exception e) {
                    log.error("Failed to send completion view-notice to {} for doc {}: {}", to, doc.getId(), e.getMessage());
                    ok = false;
                }
                notifications.add(note(to, null, ESignDocument.NotificationRole.INVITATION_CC, ok, false));
            }
        }

        return notifications;
    }

    /** Builds a single completion-notification record. */
    private ESignDocument.CompletionNotification note(String email, String name,
                                                      ESignDocument.NotificationRole role,
                                                      boolean ok, boolean withAttachment) {
        return ESignDocument.CompletionNotification.builder()
                .email(email)
                .name(name)
                .role(role)
                .status(ok ? ESignDocument.NotificationStatus.SENT : ESignDocument.NotificationStatus.FAILED)
                .withAttachment(withAttachment)
                .sentAt(LocalDateTime.now())
                .build();
    }

    /** Sends one completion email (signed PDF attached); logs and swallows failures. Returns true on success. */
    private boolean sendCompletionTo(String to, String greetingName, String subject, ESignDocument doc,
                                     String verifyLink, String orgName, byte[] signedPdfBytes, String filename) {
        try {
            String greeting = notBlank(greetingName) ? greetingName : "there";
            Map<String, Object> vars = new java.util.HashMap<>(brandVars(doc.getOrgId(), orgName));
            vars.put("signerName",        greeting);
            vars.put("signerEmail",       to != null ? to : "");
            vars.put("documentName",      doc.getTitle() != null ? doc.getTitle() : "");
            vars.put("signedOn",          fmt(doc.getCompletedAt(), SIGNED_FMT));
            vars.put("documentHashBlock", hashBlock(doc.getSignedPdfHash()));
            vars.put("verifyLink",        verifyLink);
            // legacy aliases
            vars.put("clientName",        greeting);
            vars.put("documentTitle",     doc.getTitle() != null ? doc.getTitle() : "");
            ResolvedEmail r = resolveInternal(
                    InternalTemplateCodes.ESIGN_COMPLETION_SIGNER,
                    subject, // built-in subject supplied by caller ("Signed document ready: <title>")
                    this::buildCompletionHtml);
            sendHtmlWithAttachment(to, r.subject(), r.html(), vars, signedPdfBytes, filename, orgName);
            log.info("Completion email sent to {} for doc {}", to, doc.getId());
            return true;
        } catch (Exception e) {
            log.error("Failed to send completion email to {} for doc {}: {}", to, doc.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Sends an HTML email with a single PDF attachment.
     * Uses sendHtmlEmailWithAttachment so no S3 template bucket is needed.
     */
    private void sendHtmlWithAttachment(String to, String subject, String html, Map<String, Object> vars,
                                        byte[] pdfBytes, String filename, String senderDisplayName) {
        emailDispatcher.sendHtmlEmailWithAttachment(
                to, subject, html, vars, pdfBytes, filename, senderDisplayName);
    }

    // ── HTML builders (canonical tokenised bodies; see ESignEmailTemplates) ──

    private String buildInvitationHtml() { return ESignEmailTemplates.INVITATION; }

    private String buildCompletionHtml() { return ESignEmailTemplates.COMPLETION; }

    // ── Brand + content variable assembly ────────────────────────────────────

    private static final java.time.format.DateTimeFormatter EXPIRY_FMT =
            java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy");
    private static final java.time.format.DateTimeFormatter SIGNED_FMT =
            java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a");

    private String fmt(java.time.LocalDateTime t, java.time.format.DateTimeFormatter f) {
        return t != null ? t.format(f) : "";
    }

    private long daysUntil(java.time.LocalDateTime t) {
        return t != null ? Math.max(0, java.time.Duration.between(java.time.LocalDateTime.now(), t).toDays()) : 0;
    }

    /** Human phrase for the expiry pill: "today" / "in 1 day" / "in 5 days" (empty when no expiry). */
    private String expiresInPhrase(java.time.LocalDateTime t) {
        if (t == null) return "";
        long d = daysUntil(t);
        if (d <= 0) return "today";
        return "in " + d + (d == 1 ? " day" : " days");
    }

    /** Loads the org's branding (logo, colours, reply-to, footer), or null. */
    private com.braify.feature.branding.model.OrgBranding loadBranding(String orgId) {
        if (orgId == null || orgId.isBlank()) return null;
        return orgRepo.findById(orgId)
                .map(com.braify.feature.organization.model.Organization::getBranding)
                .orElse(null);
    }

    /** Converts {@code #RRGGBB}/{@code #RGB} to an {@code rgba(...)} string with the given alpha. */
    private String hexToRgba(String hex, double alpha) {
        try {
            String h = (hex == null ? "#4F46E5" : hex.trim()).replace("#", "");
            if (h.length() == 3) {
                StringBuilder e = new StringBuilder();
                for (char c : h.toCharArray()) e.append(c).append(c);
                h = e.toString();
            }
            int n = Integer.parseInt(h, 16);
            return "rgba(" + ((n >> 16) & 255) + ", " + ((n >> 8) & 255) + ", " + (n & 255) + ", " + alpha + ")";
        } catch (Exception e) {
            return "rgba(79, 70, 229, " + alpha + ")";
        }
    }

    /** Brand tokens shared by every e-sign email: logo/initial, org name, accent palette, footer contact. */
    private Map<String, Object> brandVars(String orgId, String orgName) {
        com.braify.feature.branding.model.OrgBranding b = loadBranding(orgId);
        String accent = (b != null && notBlank(b.getPrimaryColor())) ? b.getPrimaryColor().trim() : "#4F46E5";
        String logo   = (b != null) ? b.getLogoUrl() : null;
        String initial = (orgName != null && !orgName.isBlank()) ? orgName.trim().substring(0, 1).toUpperCase() : "N";

        // Email clients (Gmail especially) block data: image URIs, so only embed a hosted
        // http(s) logo; otherwise render the reliable coloured initial badge.
        boolean hostedLogo = notBlank(logo) && (logo.startsWith("http://") || logo.startsWith("https://"));
        String brandMark = hostedLogo
                ? "<img src=\"" + logo + "\" alt=\"\" width=\"34\" height=\"34\" style=\"width:34px;height:34px;border-radius:8px;object-fit:contain;display:block;\">"
                : "<div style=\"width:34px;height:34px;line-height:34px;border-radius:8px;background:" + accent + ";color:#fff;text-align:center;font-weight:700;font-size:16px;\">" + initial + "</div>";

        Map<String, Object> m = new java.util.HashMap<>();
        m.put("organizationName", orgName != null ? orgName : "");
        m.put("orgName",          orgName != null ? orgName : "");   // legacy alias
        m.put("brandMark",        brandMark);
        m.put("accent",           accent);
        m.put("accentSoft",       hexToRgba(accent, 0.10));
        m.put("accentBorder",     hexToRgba(accent, 0.55));
        m.put("footerContact",    buildFooterContact(b, accent));
        return m;
    }

    /** "Need help? support · address" footer line, or empty when neither is configured. */
    private String buildFooterContact(com.braify.feature.branding.model.OrgBranding b, String accent) {
        String support = (b != null) ? b.getEmailReplyTo() : null;
        String address = (b != null) ? b.getFooterText()   : null;
        boolean hasS = notBlank(support), hasA = notBlank(address);
        if (!hasS && !hasA) return "";
        // Inline spans (no flexbox) so it aligns in Gmail/Outlook.
        StringBuilder sb = new StringBuilder("<div style=\"font-size:11.5px;line-height:1.7;color:#94A3B8;\">");
        if (hasS) sb.append("<span>Need help? <a href=\"mailto:").append(support)
                    .append("\" style=\"color:").append(accent).append(";font-weight:600;text-decoration:none;\">")
                    .append(support).append("</a></span>");
        if (hasS && hasA) sb.append("<span style=\"color:#CBD5E1;\"> · </span>");
        if (hasA) sb.append("<span>").append(address).append("</span>");
        return sb.append("</div>").toString();
    }

    /** The SHA-256 "document fingerprint" row for the completion email, or empty when no hash. */
    private String hashBlock(String hash) {
        if (!notBlank(hash)) return "";
        return "<div style=\"border-top:1px solid #E2E8F0;padding:12px 20px;background:#F1F5F9;\">"
             + "<div style=\"font-size:10.5px;font-weight:700;letter-spacing:0.1em;color:#94A3B8;margin-bottom:4px;\">DOCUMENT FINGERPRINT (SHA-256)</div>"
             + "<div style=\"font-family:'SF Mono',ui-monospace,Consolas,Menlo,monospace;font-size:11.5px;color:#475569;word-break:break-all;line-height:1.5;\">"
             + hash + "</div></div>";
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

    // ── INTERNAL template resolution ──────────────────────────────────────────

    private boolean notBlank(String s) { return s != null && !s.isBlank(); }

    /** Resolved (still tokenised) subject + html; tokens are substituted by the dispatcher. */
    private record ResolvedEmail(String subject, String html) {}

    /**
     * Resolves an INTERNAL system template by code. Returns its (tokenised) subject + html
     * when present, otherwise falls back to the built-in HTML so mail never breaks.
     */
    private ResolvedEmail resolveInternal(String code, String fallbackSubject, Supplier<String> fallbackHtml) {
        return emailTemplateRepo.findByCodeAndDeletedFalse(code)
                .filter(t -> notBlank(t.getHtmlContent()))
                .map(t -> new ResolvedEmail(
                        notBlank(t.getSubject()) ? t.getSubject() : fallbackSubject,
                        t.getHtmlContent()))
                .orElseGet(() -> new ResolvedEmail(fallbackSubject, fallbackHtml.get()));
    }

    /* ── INTERNAL template seeds ──────────────────────────────────────────────
       Canonical tokenised bodies live in ESignEmailTemplates; substitution values
       are assembled per-send in brandVars(...) + the send methods. */
    @Override
    public List<InternalTemplateSeed> internalTemplateSeeds() {
        return List.of(
                new InternalTemplateSeed(
                        InternalTemplateCodes.ESIGN_SIGNING_INVITATION,
                        "System — E-Sign: Signing Invitation",
                        "You have a document to sign — {{documentName}}",
                        buildInvitationHtml(),
                        List.of("organizationName", "brandMark", "accent", "accentSoft", "accentBorder",
                                "signerName", "signerEmail", "documentName", "expiryDate", "expiresIn",
                                "signingLink", "footerContact")),
                new InternalTemplateSeed(
                        InternalTemplateCodes.ESIGN_COMPLETION_SIGNER,
                        "System — E-Sign: Document Signed",
                        "Signed document ready: {{documentName}}",
                        buildCompletionHtml(),
                        List.of("organizationName", "brandMark", "accent", "accentSoft", "accentBorder",
                                "signerName", "signerEmail", "documentName", "signedOn", "documentHashBlock",
                                "verifyLink", "footerContact")),
                new InternalTemplateSeed(
                        InternalTemplateCodes.ESIGN_CC_NOTIFICATION,
                        "System — E-Sign: CC Notification",
                        "For your information: {{documentName}} sent for signature",
                        buildCcNotificationHtml(),
                        List.of("organizationName", "brandMark", "accent", "accentSoft", "ccName",
                                "signerName", "signerEmail", "documentName", "sentOn", "viewLink", "footerContact")),
                new InternalTemplateSeed(
                        InternalTemplateCodes.ESIGN_CC_COMPLETION,
                        "System — E-Sign: CC Completion",
                        "Signed: {{documentName}}",
                        buildCcCompletionHtml(),
                        List.of("organizationName", "brandMark", "accent", "accentSoft", "accentBorder",
                                "documentName", "signedOn", "viewLink", "footerContact"))
        );
    }
}
