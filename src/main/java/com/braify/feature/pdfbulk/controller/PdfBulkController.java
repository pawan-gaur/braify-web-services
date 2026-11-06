package com.braify.feature.pdfbulk.controller;

import com.braify.feature.pdfbulk.model.PdfBulkJob;
import com.braify.feature.pdfbulk.service.PdfBulkService;
import com.braify.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Tag(name = "PDF — Bulk generation", description = "Render one PDF template for every spreadsheet row; download the ZIP (also saved to Files).")
@RestController
@RequestMapping("/api/pdf/bulk-jobs")
@RequiredArgsConstructor
public class PdfBulkController {

    private final PdfBulkService service;

    @Operation(summary = "Start a bulk PDF job",
               description = "Body: `{ templateId, rows: [{column: value, …}], filenamePattern?, label? }`. " +
                             "`filenamePattern` may use `{{column}}` and `{{row}}`. Up to " + PdfBulkService.MAX_ROWS + " rows; " +
                             "each PDF counts toward the monthly document quota. Pro plan.")
    @PostMapping
    public PdfBulkJob create(@RequestBody PdfBulkService.CreateRequest body, @AuthenticationPrincipal UserDetailsImpl principal) {
        return service.create(body, principal);
    }

    @GetMapping
    public Page<PdfBulkJob> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                                 @AuthenticationPrincipal UserDetailsImpl principal) {
        return service.list(principal, page, size);
    }

    @GetMapping("/{id}")
    public PdfBulkJob get(@PathVariable String id, @AuthenticationPrincipal UserDetailsImpl principal) {
        return service.get(id, principal);
    }

    @Operation(summary = "Download link", description = "`{ url, filename }` — `url` is a pre-signed Files link, or null when the ZIP must be fetched from `/zip`.")
    @GetMapping("/{id}/download")
    public Map<String, Object> download(@PathVariable String id, @AuthenticationPrincipal UserDetailsImpl principal) {
        PdfBulkService.Download d = service.download(id, principal);
        java.util.HashMap<String, Object> m = new java.util.HashMap<>();
        m.put("url", d.url());
        m.put("filename", d.filename());
        return m;
    }

    @Operation(summary = "ZIP (temporary copy — when it couldn't be saved to Files)")
    @GetMapping("/{id}/zip")
    public ResponseEntity<Resource> zip(@PathVariable String id, @AuthenticationPrincipal UserDetailsImpl principal) {
        PdfBulkService.Download d = service.download(id, principal);
        if (d.tempFile() == null) throw new IllegalStateException("Use the download link — this ZIP is in Files.");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(d.filename(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(d.tempFile()));
    }
}
