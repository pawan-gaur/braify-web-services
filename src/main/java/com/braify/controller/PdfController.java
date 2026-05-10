package com.braify.controller;

import com.braify.model.AppUser;
import com.braify.model.PdfRequest;
import com.braify.model.Template;
import com.braify.security.UserDetailsImpl;
import com.braify.service.PdfGenerationService;
import com.braify.service.TemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PdfController {

    private final TemplateService templateService;
    private final PdfGenerationService pdfGenerationService;

    private AppUser getUser(Authentication auth) {
        return ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
    }

    @PostMapping("/generate-pdf")
    public ResponseEntity<byte[]> generatePdf(@RequestBody PdfRequest request, Authentication auth) throws Exception {
        Template template = templateService.findById(request.getTemplateId(), getUser(auth));
        byte[] pdfBytes = pdfGenerationService.generate(template, request.getData());

        String filename = request.getFilename() != null
                ? request.getFilename()
                : template.getName().replaceAll("[^a-zA-Z0-9_-]", "_") + ".pdf";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }

    /** Preview endpoint — returns PDF inline (for browser preview) */
    @PostMapping("/preview-pdf")
    public ResponseEntity<byte[]> previewPdf(@RequestBody PdfRequest request, Authentication auth) throws Exception {
        Template template = templateService.findById(request.getTemplateId(), getUser(auth));
        byte[] pdfBytes = pdfGenerationService.generate(template, request.getData());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }
}
