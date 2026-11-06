package com.braify.feature.pdfbulk.service;

import com.braify.feature.events.EventPublisher;
import com.braify.feature.events.PlatformEvent;
import com.braify.feature.events.PlatformEventTypes;
import com.braify.feature.fileupload.dto.FileMetadataRequest;
import com.braify.feature.fileupload.service.FileService;
import com.braify.feature.organization.model.Organization;
import com.braify.feature.organization.repository.OrganizationRepository;
import com.braify.feature.pdf.model.Template;
import com.braify.feature.pdf.repository.TemplateRepository;
import com.braify.feature.pdf.service.PdfGenerationService;
import com.braify.feature.pdfbulk.model.PdfBulkJob;
import com.braify.feature.pdfbulk.repository.PdfBulkJobRepository;
import com.braify.feature.quota.exception.QuotaExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Runs bulk PDF jobs on the bulk-job pool ({@link com.braify.config.AsyncConfig#BULK_EXECUTOR}): renders one PDF per row (each counts
 * toward the monthly document quota), streams them into a ZIP on disk, then saves the ZIP to
 * the org's Files. Rows are handed over in memory and never stored (they may hold personal data,
 * and spreadsheet headers aren't valid Mongo keys); jobs interrupted by a restart are marked
 * FAILED by the sweep below.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PdfBulkProcessor {

    private static final int MAX_ERRORS = 100;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^}]+?)\\s*}}");

    private final PdfBulkJobRepository   jobRepo;
    private final TemplateRepository     templateRepo;
    private final OrganizationRepository orgRepo;
    private final PdfGenerationService   pdfGenerationService;
    private final FileService            fileService;
    private final EventPublisher         eventPublisher;
    private final MongoTemplate          mongoTemplate;

    static Path tempDir() {
        return Path.of(System.getProperty("java.io.tmpdir"), "braify-bulk-pdf");
    }

    @Async(com.braify.config.AsyncConfig.BULK_EXECUTOR)
    public void processAsync(String jobId, List<Map<String, Object>> rows) {
        try {
            process(jobId, rows);
        } catch (Exception e) {
            log.error("Bulk PDF job '{}' aborted: {}", jobId, e.getMessage(), e);
            try { Files.deleteIfExists(tempDir().resolve(jobId + ".zip")); } catch (IOException ignored) { }
            mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(jobId)),
                    new Update().set("status", PdfBulkJob.Status.FAILED).set("error", "Unexpected error: " + e.getMessage())
                            .set("finishedAt", LocalDateTime.now()), PdfBulkJob.class);
        }
    }

    private void process(String jobId, List<Map<String, Object>> rows) throws IOException {
        PdfBulkJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != PdfBulkJob.Status.QUEUED) return;
        job.setStatus(PdfBulkJob.Status.RUNNING);
        job.setStartedAt(LocalDateTime.now());
        jobRepo.save(job);

        Template template = templateRepo.findByIdAndOrganizationIdAndDeletedFalse(job.getTemplateId(), job.getOrgId()).orElse(null);
        if (template == null) { fail(job, "The PDF template no longer exists."); return; }
        var branding = orgRepo.findById(job.getOrgId()).map(Organization::getBranding).orElse(null);

        Files.createDirectories(tempDir());
        Path zip = tempDir().resolve(jobId + ".zip");
        Set<String> usedNames = new HashSet<>();
        int ok = 0, bad = 0;
        boolean quotaHit = false;

        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zos = new ZipOutputStream(out)) {
            for (int i = 0; i < rows.size(); i++) {
                int rowNo = i + 1;
                if (quotaHit) { bad++; addError(job, rowNo, "Skipped — monthly document limit reached"); continue; }
                Map<String, Object> row = rows.get(i) != null ? rows.get(i) : Map.of();
                try {
                    byte[] pdf = pdfGenerationService.generate(template, row, branding);
                    zos.putNextEntry(new ZipEntry(uniqueName(fileName(job.getFilenamePattern(), template.getName(), row, rowNo), usedNames)));
                    zos.write(pdf);
                    zos.closeEntry();
                    ok++;
                } catch (QuotaExceededException e) {
                    quotaHit = true; bad++;
                    addError(job, rowNo, "Monthly document limit reached");
                } catch (Exception e) {
                    bad++;
                    addError(job, rowNo, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                }
                if (rowNo % 10 == 0) progress(jobId, ok, bad);
            }
            if (ok == 0) zos.putNextEntry(new ZipEntry("README.txt"));   // a ZIP needs at least one entry
        }

        job.setSucceeded(ok);
        job.setFailed(bad);
        job.setFinishedAt(LocalDateTime.now());
        job.setZipSizeBytes(Files.size(zip));
        if (ok == 0) {
            Files.deleteIfExists(zip);
            job.setStatus(PdfBulkJob.Status.FAILED);
            job.setError(quotaHit ? "Monthly document limit reached — no PDFs were generated." : "No PDFs could be generated.");
        } else {
            job.setStatus(bad == 0 ? PdfBulkJob.Status.COMPLETED : PdfBulkJob.Status.PARTIAL);
            storeZip(job, zip);
        }
        jobRepo.save(job);
        finished(job);
        log.info("Bulk PDF job '{}' done: ok={} failed={} file={}", jobId, ok, bad, job.getFileId());
    }

    /** Above this the ZIP isn't loaded into memory for the Files upload; it's served from disk instead. */
    private static final long MAX_ZIP_IN_MEMORY = 200L * 1024 * 1024;

    /** Saves the ZIP to Files; on failure (no storage, quota, too big…) keeps the temp file for download. */
    private void storeZip(PdfBulkJob job, Path zip) {
        try {
            if (Files.size(zip) > MAX_ZIP_IN_MEMORY) {
                throw new IllegalStateException("ZIP larger than " + (MAX_ZIP_IN_MEMORY >> 20) + " MB — kept as a temporary download");
            }
            FileMetadataRequest meta = new FileMetadataRequest();
            meta.setFolder("bulk-pdf");
            meta.setDescription("Bulk PDF: " + job.getTemplateName() + " (" + job.getSucceeded() + " documents)");
            var stored = fileService.storeGenerated(job.getOrgId(), Files.readAllBytes(zip), job.getZipFilename(),
                    "application/zip", meta, job.getCreatedByEmail(), job.getCreatedBy());
            job.setFileId(stored.getFileId());
            Files.deleteIfExists(zip);
        } catch (Exception e) {
            log.warn("Bulk PDF job '{}': ZIP not saved to Files ({}); keeping a temporary copy", job.getId(), e.getMessage());
            job.setTempZipPath(zip.toString());
        }
    }

    private void finished(PdfBulkJob job) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobId", job.getId());
        data.put("status", job.getStatus().name());
        data.put("templateId", job.getTemplateId());
        data.put("total", job.getTotal());
        data.put("succeeded", job.getSucceeded());
        data.put("failed", job.getFailed());
        if (job.getFileId() != null) data.put("fileId", job.getFileId());
        eventPublisher.publish(PlatformEvent.of(PlatformEventTypes.PDF_BULK_FINISHED, job.getOrgId(), job.getCreatedBy(),
                "Bulk PDF “" + (job.getLabel() != null ? job.getLabel() : job.getTemplateName()) + "”: "
                        + job.getSucceeded() + " of " + job.getTotal() + " generated",
                "/bulk-pdf?job=" + job.getId(), data));
    }

    private void fail(PdfBulkJob job, String message) {
        job.setStatus(PdfBulkJob.Status.FAILED);
        job.setError(message);
        job.setFinishedAt(LocalDateTime.now());
        jobRepo.save(job);
        finished(job);
    }

    private void progress(String jobId, int ok, int bad) {
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(jobId)),
                new Update().set("succeeded", ok).set("failed", bad), PdfBulkJob.class);
    }

    private static void addError(PdfBulkJob job, int row, String message) {
        if (job.getErrors().size() < MAX_ERRORS) {
            job.getErrors().add(new PdfBulkJob.RowError(row, message.length() > 300 ? message.substring(0, 300) : message));
        }
    }

    // ── File names ────────────────────────────────────────────────────────────

    /** {@code {{column}}} placeholders from the row, {@code {{row}}} = row number. Always ends in .pdf. */
    static String fileName(String pattern, String templateName, Map<String, Object> row, int rowNo) {
        String p = pattern == null || pattern.isBlank() ? templateName + "-{{row}}" : pattern;
        Matcher m = PLACEHOLDER.matcher(p);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            Object v = "row".equalsIgnoreCase(key) ? rowNo : row.get(key);
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(sb);
        String name = sb.toString().replaceAll("(?i)\\.pdf$", "")
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")   // invalid on common file systems + zip-slip
                .replaceAll("\\.{2,}", ".")
                .replaceAll("\\s+", " ").trim();
        if (name.isEmpty() || name.equals(".")) name = "document-" + rowNo;
        if (name.length() > 120) name = name.substring(0, 120);
        return name + ".pdf";
    }

    static String uniqueName(String name, Set<String> used) {
        String candidate = name;
        int n = 2;
        while (!used.add(candidate.toLowerCase(Locale.ROOT))) {
            candidate = name.substring(0, name.length() - 4) + " (" + n++ + ").pdf";
        }
        return candidate;
    }

    // ── Housekeeping ──────────────────────────────────────────────────────────

    /** Fails jobs orphaned by a restart and deletes expired temporary ZIPs. */
    @Scheduled(fixedDelay = 30 * 60 * 1000L, initialDelay = 2 * 60 * 1000L)
    public void sweep() {
        for (PdfBulkJob j : jobRepo.findByStatusInAndCreatedAtBefore(
                List.of(PdfBulkJob.Status.QUEUED, PdfBulkJob.Status.RUNNING), LocalDateTime.now().minusHours(3))) {
            j.setStatus(PdfBulkJob.Status.FAILED);
            j.setError("The job was interrupted (server restart). Please run it again.");
            j.setFinishedAt(LocalDateTime.now());
            jobRepo.save(j);
        }
        for (PdfBulkJob j : jobRepo.findByTempZipPathNotNullAndFinishedAtBefore(
                LocalDateTime.now().minusHours(PdfBulkJob.TEMP_RETENTION_HOURS))) {
            try { Files.deleteIfExists(Path.of(j.getTempZipPath())); } catch (IOException ignored) { }
            j.setTempZipPath(null);
            j.setExpired(true);
            jobRepo.save(j);
        }
    }
}
