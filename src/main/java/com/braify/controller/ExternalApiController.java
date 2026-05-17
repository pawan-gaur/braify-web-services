package com.braify.controller;

import com.braify.config.infra.email.EmailDispatcher;
import com.braify.exception.QuotaExceededException;
import com.braify.model.EmailTemplate;
import com.braify.model.OrgBranding;
import com.braify.model.Template;
import com.braify.repository.EmailTemplateRepository;
import com.braify.repository.OrganizationRepository;
import com.braify.repository.TemplateRepository;
import com.braify.security.ApiKeyPrincipal;
import com.braify.service.OrgApiKeyService;
import com.braify.service.PdfGenerationService;
import com.braify.service.QuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * External API endpoints — authenticated via X-API-Key header.
 *
 * <p>The {@link com.braify.security.ApiKeyAuthFilter} validates the key and populates the
 * SecurityContext with an {@link ApiKeyPrincipal} before any endpoint here is invoked.
 * No JWT / session is required.
 *
 * <p>Base path: {@code /api/external}
 */
@Slf4j
@RestController
@RequestMapping("/api/external")
@RequiredArgsConstructor
@Tag(name = "External API", description = "External API endpoints authenticated via X-API-Key header")
public class ExternalApiController {

    private final PdfGenerationService    pdfGenerationService;
    private final TemplateRepository      templateRepository;
    private final EmailTemplateRepository emailTemplateRepository;
    private final OrganizationRepository  organizationRepository;
    private final OrgApiKeyService        orgApiKeyService;
    private final QuotaService            quotaService;
    private final EmailDispatcher         emailDispatcher;

    // ── Principal helper ──────────────────────────────────────────────────────

    /**
     * Retrieves the {@link ApiKeyPrincipal} that the filter placed into the SecurityContext.
     *
     * @throws ResponseStatusException 401 if no API key principal is present
     */
    private ApiKeyPrincipal getPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof ApiKeyPrincipal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "API key required");
        }
        return (ApiKeyPrincipal) auth.getPrincipal();
    }

    // ── PDF endpoints ─────────────────────────────────────────────────────────

    /**
     * Generates a PDF from a template owned by the caller's organisation.
     *
     * <p>POST /api/external/pdf/generate
     * <p>Body: { "templateId": "...", "data": { "key": "value", ... } }
     *
     * @return PDF bytes with Content-Type: application/pdf
     */
    @PostMapping("/pdf/generate")
    @Operation(summary = "Generate PDF",
               description = "Renders the specified PDF template with the supplied placeholder data and returns PDF bytes.")
    public ResponseEntity<byte[]> generatePdf(@RequestBody Map<String, Object> body) {
        ApiKeyPrincipal principal = getPrincipal();
        String orgId      = principal.orgId();
        String templateId = (String) body.get("templateId");

        if (templateId == null || templateId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "templateId is required");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = body.get("data") instanceof Map
                ? (Map<String, Object>) body.get("data")
                : Map.of();

        // Verify the template belongs to this organisation
        Template template = templateRepository.findByIdAndOrganizationIdAndDeletedFalse(templateId, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Template not found or does not belong to your organisation"));

        // Resolve optional org branding
        OrgBranding branding = organizationRepository.findById(orgId)
                .map(org -> org.getBranding())
                .orElse(null);

        try {
            byte[] pdf = pdfGenerationService.generate(template, data, branding);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + sanitizeFilename(template.getName()) + ".pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);

        } catch (QuotaExceededException qe) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, qe.getMessage());
        } catch (Exception e) {
            log.error("PDF generation failed for template '{}': {}", templateId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "PDF generation failed");
        }
    }

    /**
     * Lists all active PDF templates for the caller's organisation.
     *
     * <p>GET /api/external/pdf/templates
     */
    @GetMapping("/pdf/templates")
    @Operation(summary = "List PDF templates",
               description = "Returns all active PDF templates belonging to the caller's organisation.")
    public List<Template> listPdfTemplates() {
        ApiKeyPrincipal principal = getPrincipal();
        return templateRepository.findByOrganizationIdAndDeletedFalseOrderByUpdatedAtDesc(principal.orgId());
    }

    // ── Email endpoints ───────────────────────────────────────────────────────

    /**
     * Sends an email using an email template belonging to the caller's organisation.
     *
     * <p>POST /api/external/email/send
     * <p>Body: { "templateId": "...", "to": "...", "subject": "..." (optional), "data": { ... } }
     */
    @PostMapping("/email/send")
    @Operation(summary = "Send email",
               description = "Renders the specified email template and sends it to the given recipient.")
    public ResponseEntity<Map<String, Object>> sendEmail(@RequestBody Map<String, Object> body) {
        ApiKeyPrincipal principal = getPrincipal();
        String orgId      = principal.orgId();
        String templateId = (String) body.get("templateId");
        String to         = (String) body.get("to");
        String subject    = (String) body.get("subject");

        if (templateId == null || templateId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "templateId is required");
        }
        if (to == null || to.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to is required");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = body.get("data") instanceof Map
                ? (Map<String, Object>) body.get("data")
                : Map.of();

        // Verify the template belongs to this organisation
        EmailTemplate template = emailTemplateRepository
                .findByIdAndOrganizationIdAndDeletedFalse(templateId, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Email template not found or does not belong to your organisation"));

        // Resolve subject: request → template → generic fallback
        String resolvedSubject = (subject != null && !subject.isBlank())
                ? subject
                : (template.getSubject() != null && !template.getSubject().isBlank())
                        ? template.getSubject()
                        : "Message from " + (template.getFromName() != null ? template.getFromName() : "Braify");

        try {
            var resendResponse = emailDispatcher.sendHtmlEmail(
                    to, resolvedSubject, template.getHtmlContent(), data);

            return ResponseEntity.ok(Map.of(
                    "success",   true,
                    "messageId", resendResponse != null && resendResponse.getId() != null
                                 ? resendResponse.getId() : "",
                    "to",        to,
                    "subject",   resolvedSubject
            ));

        } catch (Exception e) {
            log.error("Email send failed for template '{}': {}", templateId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Email send failed");
        }
    }

    /**
     * Lists all active email templates for the caller's organisation.
     *
     * <p>GET /api/external/email/templates
     */
    @GetMapping("/email/templates")
    @Operation(summary = "List email templates",
               description = "Returns all active email templates belonging to the caller's organisation.")
    public List<EmailTemplate> listEmailTemplates() {
        ApiKeyPrincipal principal = getPrincipal();
        return emailTemplateRepository
                .findByOrganizationIdAndDeletedFalseOrderByUpdatedAtDesc(principal.orgId());
    }

    // ── E-Sign endpoints ──────────────────────────────────────────────────────

    /**
     * Lists e-sign documents for the caller's organisation.
     *
     * <p>GET /api/external/esign/documents
     *
     * TODO: Implement once ESignDocumentRepository exposes a findByOrgIdOrderByCreatedAtDesc
     *       method that filters by non-deleted status. For now this stub returns 501.
     */
    @GetMapping("/esign/documents")
    @Operation(summary = "List e-sign documents",
               description = "Returns e-sign documents belonging to the caller's organisation.")
    public ResponseEntity<Void> listEsignDocuments() {
        // TODO: Implement when ESignDocumentRepository has the necessary query method
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Strips characters that are unsafe in Content-Disposition filenames. */
    private static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "document";
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
