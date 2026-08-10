package com.braify.feature.pdf.controller;

import com.braify.feature.user.model.AppUser;
import com.braify.feature.pdf.model.Template;
import com.braify.feature.pdf.model.TemplateVersion;
import com.braify.security.UserDetailsImpl;
import com.braify.feature.pdf.service.TemplateService;
import com.braify.feature.pdf.service.TemplateVersionService;
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
import java.util.Map;

@Slf4j
@Tag(name = "PDF Templates", description = "CRUD and version history for PDF templates. Each template stores HTML/CSS content plus GrapesJS editor state. All endpoints are org-scoped — users only see their own organisation's templates.")
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

    @Operation(summary = "List all PDF templates",
               description = "Returns all non-deleted templates belonging to the authenticated user's organisation.")
    @ApiResponse(responseCode = "200", description = "List of templates")
    @GetMapping
    public List<Template> getAll(Authentication auth) {
        log.debug("GET /api/templates caller='{}'", getUser(auth).getEmail());
        return templateService.findAll(getUser(auth));
    }

    @Operation(summary = "Get PDF template by ID")
    @ApiResponse(responseCode = "200", description = "Template found")
    @ApiResponse(responseCode = "404", description = "Template not found or not accessible")
    @GetMapping("/{id}")
    public Template getById(@Parameter(description = "Template ID") @PathVariable String id,
                            Authentication auth) {
        log.debug("GET /api/templates/{} caller='{}'", id, getUser(auth).getEmail());
        return templateService.findById(id, getUser(auth));
    }

    @Operation(summary = "Create PDF template",
               description = "Creates a new template. The body should include `name`, `htmlContent`, `cssContent`, `gjsData` (GrapesJS JSON), `pageSize` (A4/LETTER), `orientation` (PORTRAIT/LANDSCAPE), and optional margin fields.")
    @ApiResponse(responseCode = "200", description = "Template created")
    @PostMapping
    public ResponseEntity<Template> create(@Valid @RequestBody Template template, Authentication auth) {
        log.info("POST /api/templates name='{}' by '{}'", template.getName(), getUser(auth).getEmail());
        ResponseEntity<Template> result = ResponseEntity.ok(templateService.create(template, getUser(auth)));
        log.info("Template created: id='{}'", result.getBody() != null ? result.getBody().getId() : "unknown");
        return result;
    }

    @Operation(summary = "Update PDF template",
               description = "Saves a new version snapshot and replaces the template's content. Version number is auto-incremented.")
    @ApiResponse(responseCode = "200", description = "Template updated")
    @PutMapping("/{id}")
    public ResponseEntity<Template> update(@Parameter(description = "Template ID") @PathVariable String id,
                                           @Valid @RequestBody Template template,
                                           Authentication auth) {
        log.info("PUT /api/templates/{} by '{}'", id, getUser(auth).getEmail());
        ResponseEntity<Template> result = ResponseEntity.ok(templateService.update(id, template, getUser(auth)));
        log.info("Template '{}' updated", id);
        return result;
    }

    @Operation(summary = "Clone a PDF template",
               description = "Duplicates the template into the caller's org, copying all content and appending _clone to the code and name. " +
                             "Optional body { code, name } overrides the defaults. Returns 400 if the resulting code already exists.")
    @ApiResponse(responseCode = "200", description = "Template cloned")
    @ApiResponse(responseCode = "400", description = "A template with that code already exists")
    @PostMapping("/{id}/clone")
    public ResponseEntity<Template> clone(@Parameter(description = "Source template ID") @PathVariable String id,
                                          @RequestBody(required = false) Map<String, String> body,
                                          Authentication auth) {
        String code = body != null ? body.get("code") : null;
        String name = body != null ? body.get("name") : null;
        log.info("POST /api/templates/{}/clone by '{}'", id, getUser(auth).getEmail());
        return ResponseEntity.ok(templateService.clone(id, code, name, getUser(auth)));
    }

    @Operation(summary = "Soft-delete PDF template",
               description = "Marks the template as deleted. Version history is retained. The template will no longer appear in list results.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@Parameter(description = "Template ID") @PathVariable String id,
                                       Authentication auth) {
        log.info("DELETE /api/templates/{} by '{}'", id, getUser(auth).getEmail());
        templateService.delete(id, getUser(auth));
        log.info("Template '{}' deleted", id);
        return ResponseEntity.noContent().build();
    }

    // ── Version history ───────────────────────────────────────────────────────

    @Operation(summary = "List version history",
               description = "Returns all saved version snapshots for the template, newest first. Each entry stores the full HTML/CSS/GJS state at the time of save.")
    @GetMapping("/{id}/versions")
    public List<TemplateVersion> getVersions(@Parameter(description = "Template ID") @PathVariable String id) {
        log.debug("GET /api/templates/{}/versions", id);
        return versionService.getVersions(id);
    }

    @Operation(summary = "Get a specific version snapshot")
    @GetMapping("/{id}/versions/{version}")
    public TemplateVersion getVersion(@Parameter(description = "Template ID") @PathVariable String id,
                                      @Parameter(description = "Version number") @PathVariable int version) {
        log.debug("GET /api/templates/{}/versions/{}", id, version);
        return versionService.getVersion(id, version);
    }

    @Operation(summary = "Restore template to a previous version",
               description = "Copies the content of the specified version into the current template and saves a new version snapshot. The restored content becomes the latest version.")
    @ApiResponse(responseCode = "200", description = "Template restored")
    @PostMapping("/{id}/versions/{version}/restore")
    public ResponseEntity<Template> restoreVersion(@Parameter(description = "Template ID") @PathVariable String id,
                                                   @Parameter(description = "Version to restore") @PathVariable int version,
                                                   Authentication auth) {
        log.info("POST /api/templates/{}/versions/{}/restore by '{}'", id, version, getUser(auth).getEmail());
        ResponseEntity<Template> result = ResponseEntity.ok(templateService.restoreVersion(id, version, getUser(auth)));
        log.info("Template '{}' restored to version {}", id, version);
        return result;
    }
}
