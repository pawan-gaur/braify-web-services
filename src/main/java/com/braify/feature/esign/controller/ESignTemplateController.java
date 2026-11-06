package com.braify.feature.esign.controller;

import com.braify.feature.esign.dto.DocumentResponse;
import com.braify.feature.esign.model.ESignTemplate;
import com.braify.feature.esign.service.ESignTemplateService;
import com.braify.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reusable e-sign templates (shared within the organisation). */
@Tag(name = "E-Sign — Templates", description = "Save a document's PDF and field layout as a reusable template; create new documents from it by filling each signer role.")
@RestController
@RequestMapping("/api/esign/templates")
@RequiredArgsConstructor
public class ESignTemplateController {

    private final ESignTemplateService service;

    @Operation(summary = "List the organisation's e-sign templates")
    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal UserDetailsImpl principal) {
        return service.list(principal).stream().map(ESignTemplateController::view).toList();
    }

    @Operation(summary = "Get a template (with its field layout)")
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id, @AuthenticationPrincipal UserDetailsImpl principal) {
        ESignTemplate t = service.get(id, principal);
        Map<String, Object> m = view(t);
        m.put("fields", t.getFields());
        return m;
    }

    @Operation(summary = "Template PDF (for preview)")
    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String id, @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition", "inline")
                .body(service.pdf(id, principal));
    }

    @Operation(summary = "Save a document as a template",
               description = "Body: `{ name, description?, roles?[] }` — `roles` names the signer roles in signing order (defaults to Signer 1…n). Signer-entered values are never copied.")
    @PostMapping("/from-document/{docId}")
    public Map<String, Object> saveFromDocument(@PathVariable String docId,
                                                @RequestBody ESignTemplateService.SaveRequest body,
                                                @AuthenticationPrincipal UserDetailsImpl principal) {
        return view(service.saveFromDocument(docId, body, principal));
    }

    @Operation(summary = "Update a template", description = "Body: `{ name?, description?, documentTitle?, roles?[], signingMode? }`")
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody ESignTemplateService.UpdateRequest body,
                                      @AuthenticationPrincipal UserDetailsImpl principal) {
        return view(service.update(id, body, principal));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, @AuthenticationPrincipal UserDetailsImpl principal) {
        service.delete(id, principal);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Create a DRAFT document from a template",
               description = "Body: `{ title?, signatories: [{name, email}] (one per role, in role order), ccEmails?, completionCcEmails? }`. " +
                             "Returns the draft with fields placed — review it in the builder and send.")
    @PostMapping("/{id}/use")
    public DocumentResponse use(@PathVariable String id, @RequestBody ESignTemplateService.UseRequest body,
                                @AuthenticationPrincipal UserDetailsImpl principal, HttpServletRequest http) {
        return service.use(id, body, principal, extractIp(http), http.getHeader("User-Agent"));
    }

    private static Map<String, Object> view(ESignTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("name", t.getName());
        m.put("description", t.getDescription());
        m.put("documentTitle", t.getDocumentTitle());
        m.put("signingMode", t.getSigningMode());
        m.put("roles", t.getRoles());
        m.put("fieldCount", t.getFields() == null ? 0 : t.getFields().size());
        m.put("createdBy", t.getCreatedBy());
        m.put("createdByEmail", t.getCreatedByEmail());
        m.put("useCount", t.getUseCount());
        m.put("lastUsedAt", t.getLastUsedAt());
        m.put("createdAt", t.getCreatedAt());
        m.put("updatedAt", t.getUpdatedAt());
        return m;
    }

    private static String extractIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        String xri = request.getHeader("X-Real-IP");
        if (xri != null && !xri.isBlank()) return xri.trim();
        return request.getRemoteAddr();
    }
}
