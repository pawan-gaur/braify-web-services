package com.braify.feature.pdf.controller;

import com.braify.feature.user.model.AppUser;
import com.braify.feature.branding.model.OrgBranding;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.pdf.model.PdfRequest;
import com.braify.feature.pdf.model.Template;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.security.UserDetailsImpl;
import com.braify.feature.pdf.service.PdfGenerationService;
import com.braify.feature.pdf.service.TemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Tag(name = "PDF Generation", description = "Render a PDF template with data and download the result. Quota is enforced on `/generate-pdf` (counts against the org's monthly doc limit). `/preview-pdf` is quota-free and returns the PDF inline.")
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PdfController {

    private final TemplateService         templateService;
    private final PdfGenerationService    pdfGenerationService;
    private final OrganizationRepository  orgRepository;

    private AppUser getUser(Authentication auth) {
        return ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
    }

    /** Resolves branding for a template's owning org; returns null if not configured. */
    private OrgBranding resolveBranding(String orgId) {
        if (orgId == null) return null;
        return orgRepository.findById(orgId)
                .map(Organization::getBranding)
                .orElse(null);
    }

    @Operation(summary = "Generate and download PDF",
               description = "Renders the template by substituting `{{placeholder}}` variables with the supplied `data` map, " +
                             "applies org branding (logo, `--brand-color`, footer), and returns the PDF as a downloadable attachment.\n\n" +
                             "**Quota:** increments the org's monthly document counter. Returns HTTP 429 if the limit is exceeded.\n\n" +
                             "Body: `{ templateId, data: { key: value, … }, filename? }`")
    @ApiResponse(responseCode = "200", description = "PDF binary (application/pdf)")
    @ApiResponse(responseCode = "429", description = "Monthly document quota exceeded")
    @PostMapping("/generate-pdf")
    public ResponseEntity<byte[]> generatePdf(@RequestBody PdfRequest request,
                                               Authentication auth) throws Exception {
        Template    template = templateService.findById(request.getTemplateId(), getUser(auth));
        OrgBranding branding = resolveBranding(template.getOrganizationId());
        byte[]      pdfBytes = pdfGenerationService.generate(template, request.getData(), branding);

        String filename = request.getFilename() != null
                ? request.getFilename()
                : template.getName().replaceAll("[^a-zA-Z0-9_-]", "_") + ".pdf";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }

    @Operation(summary = "Preview PDF (no quota charge)",
               description = "Same rendering as `/generate-pdf` but returns the PDF `inline` (for browser preview) and does **not** increment the monthly quota counter. " +
                             "Use this for the builder's real-time preview.\n\n" +
                             "Body: `{ templateId, data: { key: value, … } }`")
    @ApiResponse(responseCode = "200", description = "PDF binary inline (application/pdf)")
    @PostMapping("/preview-pdf")
    public ResponseEntity<byte[]> previewPdf(@RequestBody PdfRequest request,
                                              Authentication auth) throws Exception {
        Template    template = templateService.findById(request.getTemplateId(), getUser(auth));
        OrgBranding branding = resolveBranding(template.getOrganizationId());
        byte[]      pdfBytes = pdfGenerationService.generate(template, request.getData(), branding);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }
}
