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
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
        return emailTemplateService.findAll(getUser(auth));
    }

    @Operation(summary = "Get email template by ID")
    @ApiResponse(responseCode = "200", description = "Email template found")
    @ApiResponse(responseCode = "404", description = "Not found or not accessible")
    @GetMapping("/{id}")
    public EmailTemplate getById(@Parameter(description = "Template ID") @PathVariable String id,
                                 Authentication auth) {
        return emailTemplateService.findById(id, getUser(auth));
    }

    @Operation(summary = "Create email template",
               description = "Creates a new email template. Body fields: `name`, `subject`, `previewText`, `fromName`, `htmlContent`, `cssContent`, `gjsData` (GrapesJS JSON), `placeholders` (list of variable names).")
    @ApiResponse(responseCode = "200", description = "Email template created")
    @PostMapping
    public ResponseEntity<EmailTemplate> create(@RequestBody EmailTemplate template,
                                                Authentication auth) {
        return ResponseEntity.ok(emailTemplateService.create(template, getUser(auth)));
    }

    @Operation(summary = "Update email template",
               description = "Saves a new version snapshot and replaces the template's content.")
    @ApiResponse(responseCode = "200", description = "Email template updated")
    @PutMapping("/{id}")
    public ResponseEntity<EmailTemplate> update(@Parameter(description = "Template ID") @PathVariable String id,
                                                @RequestBody EmailTemplate template,
                                                Authentication auth) {
        return ResponseEntity.ok(emailTemplateService.update(id, template, getUser(auth)));
    }

    @Operation(summary = "Soft-delete email template",
               description = "Marks the template as deleted. Version history is retained.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@Parameter(description = "Template ID") @PathVariable String id,
                                       Authentication auth) {
        emailTemplateService.delete(id, getUser(auth));
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
                                                  @RequestBody SendEmailRequest req,
                                                  Authentication auth) {
        return ResponseEntity.ok(emailTemplateService.sendEmail(id, req, getUser(auth)));
    }

    // ── Version history ───────────────────────────────────────────────────────

    @Operation(summary = "List email template version history",
               description = "Returns all saved snapshots, newest first.")
    @GetMapping("/{id}/versions")
    public List<EmailTemplateVersion> getVersions(@Parameter(description = "Template ID") @PathVariable String id) {
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
        return ResponseEntity.ok(emailTemplateService.restoreVersion(id, version, getUser(auth)));
    }
}
