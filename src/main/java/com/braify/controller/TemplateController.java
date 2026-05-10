package com.braify.controller;

import com.braify.model.AppUser;
import com.braify.model.Template;
import com.braify.model.TemplateVersion;
import com.braify.security.UserDetailsImpl;
import com.braify.service.TemplateService;
import com.braify.service.TemplateVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService        templateService;
    private final TemplateVersionService versionService;

    private AppUser getUser(Authentication auth) {
        return ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
    }

    // ── Template CRUD ─────────────────────────────────────────────────────────

    @GetMapping
    public List<Template> getAll(Authentication auth) {
        return templateService.findAll(getUser(auth));
    }

    @GetMapping("/{id}")
    public Template getById(@PathVariable String id, Authentication auth) {
        return templateService.findById(id, getUser(auth));
    }

    @PostMapping
    public ResponseEntity<Template> create(@RequestBody Template template, Authentication auth) {
        return ResponseEntity.ok(templateService.create(template, getUser(auth)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Template> update(@PathVariable String id,
                                           @RequestBody Template template,
                                           Authentication auth) {
        return ResponseEntity.ok(templateService.update(id, template, getUser(auth)));
    }

    /** Soft-delete — marks the template deleted but retains history. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, Authentication auth) {
        templateService.delete(id, getUser(auth));
        return ResponseEntity.noContent().build();
    }

    // ── Version history ───────────────────────────────────────────────────────

    /** List all saved versions for a template, newest first. */
    @GetMapping("/{id}/versions")
    public List<TemplateVersion> getVersions(@PathVariable String id) {
        return versionService.getVersions(id);
    }

    /** Fetch a specific version snapshot. */
    @GetMapping("/{id}/versions/{version}")
    public TemplateVersion getVersion(@PathVariable String id,
                                      @PathVariable int version) {
        return versionService.getVersion(id, version);
    }

    /**
     * Restore the template to an earlier version.
     * Creates a new version snapshot of the restored content.
     */
    @PostMapping("/{id}/versions/{version}/restore")
    public ResponseEntity<Template> restoreVersion(@PathVariable String id,
                                                   @PathVariable int version,
                                                   Authentication auth) {
        return ResponseEntity.ok(templateService.restoreVersion(id, version, getUser(auth)));
    }
}
