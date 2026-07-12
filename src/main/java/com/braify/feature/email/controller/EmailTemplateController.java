package com.braify.feature.email.controller;

import com.braify.feature.email.dto.SendEmailRequest;
import com.braify.feature.email.dto.SendEmailResponse;
import com.braify.feature.user.model.AppUser;
import com.braify.feature.email.model.EmailTemplate;
import com.braify.feature.email.model.EmailTemplateVersion;
import com.braify.security.UserDetailsImpl;
import com.braify.feature.email.service.EmailTemplateService;
import com.braify.feature.email.service.EmailTemplateVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Tag(name = "Email Templates", description = "CRUD, version history, and email dispatch for HTML email templates. Emails are sent via the Resend API. Templates support placeholder substitution using {{variable}} syntax.")
@RestController
@RequestMapping("/api/email-templates")
@RequiredArgsConstructor
public class EmailTemplateController {

    private final EmailTemplateService        emailTemplateService;
    private final EmailTemplateVersionService versionService;

    private AppUser getUser(Authentication auth) {
        return ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    @Operation(summary = "List all email templates",
               description = "Returns all non-deleted email templates for the authenticated user's organisation.")
    @ApiResponse(responseCode = "200", description = "List of email templates")
    @GetMapping
    public List<EmailTemplate> getAll(Authentication auth) {
        log.debug("GET /api/email-templates caller='{}'", getUser(auth).getEmail());
        return emailTemplateService.findAll(getUser(auth));
    }

    @Operation(summary = "List INTERNAL system templates",
               description = "Platform-admin only. Returns the platform's built-in system email templates " +
                             "(invite, password reset, onboarding, e-sign) — the ones addressed internally by code.")
    @ApiResponse(responseCode = "200", description = "List of INTERNAL system templates")
    @GetMapping("/internal")
    public List<EmailTemplate> getInternal(Authentication auth) {
        log.debug("GET /api/email-templates/internal caller='{}'", getUser(auth).getEmail());
        return emailTemplateService.findInternal(getUser(auth));
    }

    @Operation(summary = "Get email template by ID")
    @ApiResponse(responseCode = "200", description = "Email template found")
    @ApiResponse(responseCode = "404", description = "Not found or not accessible")
    @GetMapping("/{id}")
    public EmailTemplate getById(@Parameter(description = "Template ID") @PathVariable String id,
                                 Authentication auth) {
        log.debug("GET /api/email-templates/{}", id);
        return emailTemplateService.findById(id, getUser(auth));
    }

    @Operation(summary = "Create email template",
               description = "Creates a new email template. Body fields: `name`, `subject`, `previewText`, `fromName`, `htmlContent`, `cssContent`, `gjsData` (GrapesJS JSON), `placeholders` (list of variable names).")
    @ApiResponse(responseCode = "200", description = "Email template created")
    @PostMapping
    public ResponseEntity<EmailTemplate> create(@Valid @RequestBody EmailTemplate template,
                                                Authentication auth) {
        log.info("POST /api/email-templates name='{}' by '{}'", template.getName(), getUser(auth).getEmail());
        ResponseEntity<EmailTemplate> result = ResponseEntity.ok(emailTemplateService.create(template, getUser(auth)));
        log.info("Email template created: id='{}'", result.getBody() != null ? result.getBody().getId() : "unknown");
        return result;
    }

    @Operation(summary = "Update email template",
               description = "Saves a new version snapshot and replaces the template's content.")
    @ApiResponse(responseCode = "200", description = "Email template updated")
    @PutMapping("/{id}")
    public ResponseEntity<EmailTemplate> update(@Parameter(description = "Template ID") @PathVariable String id,
                                                @Valid @RequestBody EmailTemplate template,
                                                Authentication auth) {
        log.info("PUT /api/email-templates/{} by '{}'", id, getUser(auth).getEmail());
        ResponseEntity<EmailTemplate> result = ResponseEntity.ok(emailTemplateService.update(id, template, getUser(auth)));
        log.info("Email template updated: id='{}'", id);
        return result;
    }

    @Operation(summary = "Soft-delete email template",
               description = "Marks the template as deleted. Version history is retained.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@Parameter(description = "Template ID") @PathVariable String id,
                                       Authentication auth) {
        log.info("DELETE /api/email-templates/{} by '{}'", id, getUser(auth).getEmail());
        emailTemplateService.delete(id, getUser(auth));
        log.info("Email template deleted: id='{}'", id);
        return ResponseEntity.noContent().build();
    }

    // ── Send via Resend ───────────────────────────────────────────────────────

    @Operation(summary = "Send email using template",
               description = "Renders the template by substituting `{{placeholder}}` variables with the supplied values, then dispatches the email to the recipient via the Resend API.\n\n" +
                             "Body: `{ toEmail, toName, data: { key: value, … } }`\n\n" +
                             "The `fromName` and `replyTo` fields fall back to the org's branding settings if not set on the template.")
    @ApiResponse(responseCode = "200", description = "Email dispatched — includes Resend message ID")
    @ApiResponse(responseCode = "400", description = "Missing required fields or Resend error")
    @PostMapping("/{id}/send")
    public ResponseEntity<SendEmailResponse> send(@Parameter(description = "Template ID") @PathVariable String id,
                                                  @Valid @RequestBody SendEmailRequest req,
                                                  Authentication auth) {
        log.info("POST /api/email-templates/{}/send to='{}' by '{}'", id, req.getTo(), getUser(auth).getEmail());
        ResponseEntity<SendEmailResponse> result = ResponseEntity.ok(emailTemplateService.sendEmail(id, req, getUser(auth)));
        log.info("Email sent via template '{}' to '{}'", id, req.getTo());
        return result;
    }

    // ── Version history ───────────────────────────────────────────────────────

    @Operation(summary = "List email template version history",
               description = "Returns all saved snapshots, newest first.")
    @GetMapping("/{id}/versions")
    public List<EmailTemplateVersion> getVersions(@Parameter(description = "Template ID") @PathVariable String id) {
        log.debug("GET /api/email-templates/{}/versions", id);
        return versionService.getVersions(id);
    }

    @Operation(summary = "Get a specific email template version")
    @GetMapping("/{id}/versions/{version}")
    public EmailTemplateVersion getVersion(@Parameter(description = "Template ID") @PathVariable String id,
                                           @Parameter(description = "Version number") @PathVariable int version) {
        return versionService.getVersion(id, version);
    }

    @Operation(summary = "Restore email template to a previous version",
               description = "Copies the specified version's content into the current template and creates a new snapshot.")
    @ApiResponse(responseCode = "200", description = "Template restored")
    @PostMapping("/{id}/versions/{version}/restore")
    public ResponseEntity<EmailTemplate> restoreVersion(@Parameter(description = "Template ID") @PathVariable String id,
                                                        @Parameter(description = "Version to restore") @PathVariable int version,
                                                        Authentication auth) {
        log.info("POST /api/email-templates/{}/versions/{}/restore by '{}'", id, version, getUser(auth).getEmail());
        ResponseEntity<EmailTemplate> result = ResponseEntity.ok(emailTemplateService.restoreVersion(id, version, getUser(auth)));
        log.info("Email template '{}' restored to version {}", id, version);
        return result;
    }
}
