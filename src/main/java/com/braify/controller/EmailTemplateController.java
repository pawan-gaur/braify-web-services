package com.braify.controller;

import com.braify.dto.SendEmailRequest;
import com.braify.dto.SendEmailResponse;
import com.braify.model.AppUser;
import com.braify.model.EmailTemplate;
import com.braify.model.EmailTemplateVersion;
import com.braify.security.UserDetailsImpl;
import com.braify.service.EmailTemplateService;
import com.braify.service.EmailTemplateVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    @GetMapping
    public List<EmailTemplate> getAll(Authentication auth) {
        return emailTemplateService.findAll(getUser(auth));
    }

    @GetMapping("/{id}")
    public EmailTemplate getById(@PathVariable String id, Authentication auth) {
        return emailTemplateService.findById(id, getUser(auth));
    }

    @PostMapping
    public ResponseEntity<EmailTemplate> create(@RequestBody EmailTemplate template,
                                                Authentication auth) {
        return ResponseEntity.ok(emailTemplateService.create(template, getUser(auth)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<EmailTemplate> update(@PathVariable String id,
                                                @RequestBody EmailTemplate template,
                                                Authentication auth) {
        return ResponseEntity.ok(emailTemplateService.update(id, template, getUser(auth)));
    }

    /** Soft-delete — marks deleted but retains all history. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, Authentication auth) {
        emailTemplateService.delete(id, getUser(auth));
        return ResponseEntity.noContent().build();
    }

    // ── Send via Resend ───────────────────────────────────────────────────────

    /**
     * POST /api/email-templates/{id}/send
     * Renders the template with the supplied placeholder values and dispatches
     * it to the given recipient via the Resend API.
     */
    @PostMapping("/{id}/send")
    public ResponseEntity<SendEmailResponse> send(@PathVariable String id,
                                                  @RequestBody SendEmailRequest req,
                                                  Authentication auth) {
        return ResponseEntity.ok(emailTemplateService.sendEmail(id, req, getUser(auth)));
    }

    // ── Version history ───────────────────────────────────────────────────────

    @GetMapping("/{id}/versions")
    public List<EmailTemplateVersion> getVersions(@PathVariable String id) {
        return versionService.getVersions(id);
    }

    @GetMapping("/{id}/versions/{version}")
    public EmailTemplateVersion getVersion(@PathVariable String id,
                                           @PathVariable int version) {
        return versionService.getVersion(id, version);
    }

    @PostMapping("/{id}/versions/{version}/restore")
    public ResponseEntity<EmailTemplate> restoreVersion(@PathVariable String id,
                                                        @PathVariable int version,
                                                        Authentication auth) {
        return ResponseEntity.ok(emailTemplateService.restoreVersion(id, version, getUser(auth)));
    }
}
