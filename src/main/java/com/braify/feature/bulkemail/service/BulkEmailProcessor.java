package com.braify.feature.bulkemail.service;

import com.braify.config.infra.email.EmailDispatcher;
import com.braify.feature.bulkemail.model.BulkEmailJob;
import com.braify.feature.bulkemail.repository.BulkEmailJobRepository;
import com.braify.feature.pdf.model.Template;
import com.braify.feature.pdf.repository.TemplateRepository;
import com.braify.feature.pdf.service.PdfGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Separate Spring bean that owns the {@code @Async} bulk-email processing method.
 *
 * <p>Spring's {@code @Async} proxy only intercepts calls that cross a bean boundary.
 * Calling {@code this.processJobAsync()} inside {@link BulkEmailService} would bypass
 * the proxy and run synchronously, blocking the HTTP response thread until every email
 * is sent.  By moving the method here, {@code BulkEmailService} calls it through the
 * proxy and the method executes on Spring's async executor thread pool.
 *
 * <h3>Performance design</h3>
 * <ul>
 *   <li>{@code CONCURRENCY} emails are sent in parallel via a dedicated thread pool.</li>
 *   <li>No per-row {@code Thread.sleep} — the thread pool acts as the natural throttle.</li>
 *   <li>DB progress is flushed in two tiers:
 *       <ol>
 *         <li>Every {@code COUNTER_FLUSH_EVERY} rows a lightweight MongoDB {@code $set}
 *             updates only the three counter fields (no row-array serialisation).</li>
 *         <li>Every {@code ROWS_FLUSH_EVERY} rows a full document save persists the
 *             per-row status changes so the Recipients tab reflects live progress.</li>
 *       </ol>
 *   </li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BulkEmailProcessor {

    // ── Tuning knobs ──────────────────────────────────────────────────────────
    /** Number of emails sent concurrently. Adjust to match your email provider's rate limit. */
    private static final int CONCURRENCY        = 10;

    /**
     * Lightweight counter-only MongoDB {@code $set} every N rows processed.
     * Updates {@code sentCount / failedCount / pendingCount} without serialising
     * the entire rows array — very cheap on Atlas.
     */
    private static final int COUNTER_FLUSH_EVERY = 5;

    /**
     * Full document save (including per-row statuses) every N rows processed.
     * More expensive — choose a value that balances live-status granularity vs DB load.
     */
    private static final int ROWS_FLUSH_EVERY    = 50;

    // ── Dependencies ──────────────────────────────────────────────────────────
    private final BulkEmailJobRepository jobRepo;
    private final TemplateRepository     pdfTemplateRepo;
    private final EmailDispatcher        emailDispatcher;
    private final PdfGenerationService   pdfGenerationService;
    private final MongoTemplate          mongoTemplate;

    /** Shared RestTemplate — thread-safe; reused across all jobs. */
    private final RestTemplate restTemplate = new RestTemplate();

    // ── Entry point ───────────────────────────────────────────────────────────

    @Async
    public void processJobAsync(String jobId) {
        BulkEmailJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null) {
            log.error("BulkEmailJob {} not found for async processing", jobId);
            return;
        }

        job.setStatus(BulkEmailJob.JobStatus.PROCESSING);
        job.setStartedAt(LocalDateTime.now());
        addAuditEvent(job, BulkEmailJob.BulkEmailAuditEvent.EventType.PROCESSING_STARTED,
                "Email dispatch started");
        jobRepo.save(job);

        // Pre-load PDF template once (shared read-only across worker threads)
        Template pdfTemplate = null;
        if (job.getAttachmentType() == BulkEmailJob.AttachmentType.PDF_TEMPLATE) {
            pdfTemplate = pdfTemplateRepo.findById(job.getPdfTemplateId()).orElse(null);
            if (pdfTemplate == null) {
                log.error("PDF template {} not found — failing job {}", job.getPdfTemplateId(), jobId);
                markAllFailed(job, "PDF template not found");
                return;
            }
        }

        // Seed counters from any rows already processed (retry-pending / resend path)
        int initialSent   = (int) job.getRows().stream()
                .filter(r -> r.getStatus() == BulkEmailJob.BulkEmailRow.RowStatus.SENT).count();
        int initialFailed = (int) job.getRows().stream()
                .filter(r -> r.getStatus() == BulkEmailJob.BulkEmailRow.RowStatus.FAILED).count();

        List<BulkEmailJob.BulkEmailRow> pendingRows = job.getRows().stream()
                .filter(r -> r.getStatus() == BulkEmailJob.BulkEmailRow.RowStatus.PENDING)
                .toList();

        if (pendingRows.isEmpty()) {
            log.info("BulkEmailJob '{}' has no pending rows — nothing to do", jobId);
            finalizeJob(job, initialSent, initialFailed);
            return;
        }

        log.info("BulkEmailJob '{}' starting: {} pending rows, {} workers",
                jobId, pendingRows.size(), Math.min(CONCURRENCY, pendingRows.size()));

        // ── Parallel dispatch ─────────────────────────────────────────────────
        AtomicInteger sentCount    = new AtomicInteger(initialSent);
        AtomicInteger failedCount  = new AtomicInteger(initialFailed);
        AtomicInteger doneCount    = new AtomicInteger(0);

        final Template finalPdfTemplate = pdfTemplate;
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(CONCURRENCY, pendingRows.size()));

        List<CompletableFuture<Void>> futures = pendingRows.stream()
                .map(row -> CompletableFuture.runAsync(
                        () -> processRow(job, row, finalPdfTemplate,
                                sentCount, failedCount, doneCount),
                        pool))
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            log.error("Unexpected error waiting for job {} workers: {}", jobId, e.getMessage(), e);
        } finally {
            pool.shutdownNow();
        }

        finalizeJob(job, sentCount.get(), failedCount.get());
        log.info("BulkEmailJob '{}' done: sent={} failed={} status={}",
                jobId, sentCount.get(), failedCount.get(), job.getStatus());
    }

    // ── Per-row worker (runs on the thread-pool) ──────────────────────────────

    private void processRow(BulkEmailJob job,
                            BulkEmailJob.BulkEmailRow row,
                            Template pdfTemplate,
                            AtomicInteger sentCount,
                            AtomicInteger failedCount,
                            AtomicInteger doneCount) {

        if (Thread.currentThread().isInterrupted()) return;

        try {
            // 1. Build email placeholder map
            Map<String, Object> emailPlaceholders = new HashMap<>();
            if (job.getColumnMapping() != null) {
                job.getColumnMapping().forEach((placeholder, xlsxCol) ->
                        emailPlaceholders.put(placeholder, row.getData().getOrDefault(xlsxCol, "")));
            }
            emailPlaceholders.put("email", row.getRecipientEmail());
            if (row.getRecipientName() != null && !row.getRecipientName().isBlank())
                emailPlaceholders.put("name", row.getRecipientName());

            // 2. Resolve subject
            String subject = substituteVars(job.getEmailTemplateSubject(), row.getData());

            // 3. Build attachments — primary type + optional secondary Excel sheet
            //    Stored as an ordered map so filename → bytes pairs are sent in order.
            Map<String, byte[]> attachments = new java.util.LinkedHashMap<>();

            switch (job.getAttachmentType()) {
                case UPLOAD -> {
                    String name = job.getUploadedPdfName() != null
                            ? job.getUploadedPdfName() : "attachment.pdf";
                    if (job.getUploadedPdfData() != null)
                        attachments.put(name, job.getUploadedPdfData());
                }
                case PDF_TEMPLATE -> {
                    Map<String, Object> pdfData = new HashMap<>();
                    if (job.getPdfColumnMapping() != null) {
                        job.getPdfColumnMapping().forEach((pdfVar, xlsxCol) ->
                                pdfData.put(pdfVar, row.getData().getOrDefault(xlsxCol, "")));
                    } else {
                        pdfData.putAll(row.getData());
                    }
                    String pdfName = sanitize(job.getPdfTemplateName() != null
                            ? job.getPdfTemplateName() : "document") + ".pdf";
                    attachments.put(pdfName, pdfGenerationService.generate(pdfTemplate, pdfData));
                }
                case EXTERNAL_API -> {
                    attachments.put("attachment.pdf", fetchExternalPdf(job, row));
                }
                case EXCEL_SHEET -> {
                    String idValue = row.getData().getOrDefault(job.getMainSheetIdColumn(), "");
                    String tpl     = (job.getDetailSheetFileName() != null
                            && !job.getDetailSheetFileName().isBlank())
                            ? job.getDetailSheetFileName()
                            : "details_{{" + job.getMainSheetIdColumn() + "}}.xlsx";
                    attachments.put(substituteVars(tpl, row.getData()), generateDetailExcel(job, idValue));
                }
                default -> { /* NONE — no primary attachment */ }
            }

            // Secondary: also attach Excel from Sheet 2 when requested and Sheet 2 data is present.
            // Skipped when the primary type is already EXCEL_SHEET (avoid duplicate attachment).
            if (job.isIncludeExcelSheet()
                    && job.getAttachmentType() != BulkEmailJob.AttachmentType.EXCEL_SHEET
                    && job.getDetailSheetIdColumn() != null && job.getMainSheetIdColumn() != null) {
                String idValue  = row.getData().getOrDefault(job.getMainSheetIdColumn(), "");
                String tpl      = (job.getDetailSheetFileName() != null
                        && !job.getDetailSheetFileName().isBlank())
                        ? job.getDetailSheetFileName()
                        : "details_{{" + job.getMainSheetIdColumn() + "}}.xlsx";
                attachments.put(substituteVars(tpl, row.getData()), generateDetailExcel(job, idValue));
            }

            // 4. Resolve CC recipients from configured columns
            List<String> ccEmails = null;
            if (job.getCcColumns() != null && !job.getCcColumns().isEmpty()) {
                ccEmails = job.getCcColumns().stream()
                        .map(col -> row.getData().getOrDefault(col, "").trim())
                        .filter(addr -> !addr.isBlank() && addr.contains("@"))
                        .distinct()
                        .toList();
                if (ccEmails.isEmpty()) ccEmails = null;
            }

            // 5. Send
            String senderName = (job.getOrgName() != null && !job.getOrgName().isBlank())
                    ? job.getOrgName() : job.getEmailTemplateName();

            var response = attachments.isEmpty()
                    ? emailDispatcher.sendHtmlEmail(
                            row.getRecipientEmail(), ccEmails, subject,
                            job.getEmailTemplateHtml(), emailPlaceholders, senderName)
                    : emailDispatcher.sendHtmlEmailWithAttachments(
                            row.getRecipientEmail(), ccEmails, subject,
                            job.getEmailTemplateHtml(), emailPlaceholders,
                            attachments, senderName);

            row.setStatus(BulkEmailJob.BulkEmailRow.RowStatus.SENT);
            row.setSentAt(LocalDateTime.now());
            row.setMessageId(response != null ? response.getId() : null);
            sentCount.incrementAndGet();

        } catch (Exception ex) {
            log.warn("Row {} failed in job {}: {}", row.getRowIndex(), job.getId(), ex.getMessage());
            row.setStatus(BulkEmailJob.BulkEmailRow.RowStatus.FAILED);
            row.setError(truncate(ex.getMessage(), 200));
            failedCount.incrementAndGet();
        }

        // ── Tiered progress persistence ───────────────────────────────────────
        int done   = doneCount.incrementAndGet();
        int sent   = sentCount.get();
        int failed = failedCount.get();
        int pending = job.getTotalCount() - sent - failed;

        if (done % ROWS_FLUSH_EVERY == 0) {
            // Tier 2: full save — persists per-row statuses for the Recipients tab
            synchronized (job) {
                job.setSentCount(sent);
                job.setFailedCount(failed);
                job.setPendingCount(Math.max(0, pending));
                jobRepo.save(job);
            }
        } else if (done % COUNTER_FLUSH_EVERY == 0) {
            // Tier 1: lightweight $set — only touches 3 counter fields, no row-array I/O
            flushCounters(job.getId(), sent, failed, Math.max(0, pending));
        }
    }

    // ── Finalize job after all workers complete ───────────────────────────────

    private void finalizeJob(BulkEmailJob job, int sent, int failed) {
        job.setSentCount(sent);
        job.setFailedCount(failed);
        job.setPendingCount(0);
        job.setCompletedAt(LocalDateTime.now());

        if (failed == 0) {
            job.setStatus(BulkEmailJob.JobStatus.COMPLETED);
            addAuditEvent(job, BulkEmailJob.BulkEmailAuditEvent.EventType.JOB_COMPLETED,
                    "All " + sent + " email(s) delivered successfully");
        } else if (sent == 0) {
            job.setStatus(BulkEmailJob.JobStatus.FAILED);
            addAuditEvent(job, BulkEmailJob.BulkEmailAuditEvent.EventType.JOB_FAILED,
                    "All " + failed + " recipient(s) failed to send");
        } else {
            job.setStatus(BulkEmailJob.JobStatus.PARTIAL);
            addAuditEvent(job, BulkEmailJob.BulkEmailAuditEvent.EventType.JOB_PARTIAL,
                    sent + " sent, " + failed + " failed out of " + job.getTotalCount());
        }
        jobRepo.save(job);
    }

    // ── MongoDB $set — counters only, no row-array serialisation ─────────────

    private void flushCounters(String jobId, int sent, int failed, int pending) {
        try {
            mongoTemplate.updateFirst(
                    Query.query(Criteria.where("_id").is(jobId)),
                    new Update()
                            .set("sentCount",    sent)
                            .set("failedCount",  failed)
                            .set("pendingCount", pending),
                    BulkEmailJob.class);
        } catch (Exception e) {
            log.warn("Counter flush failed for job {}: {}", jobId, e.getMessage());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void markAllFailed(BulkEmailJob job, String error) {
        job.getRows().stream()
                .filter(r -> r.getStatus() == BulkEmailJob.BulkEmailRow.RowStatus.PENDING)
                .forEach(r -> {
                    r.setStatus(BulkEmailJob.BulkEmailRow.RowStatus.FAILED);
                    r.setError(error);
                });
        job.setFailedCount((int) job.getRows().stream()
                .filter(r -> r.getStatus() == BulkEmailJob.BulkEmailRow.RowStatus.FAILED).count());
        job.setPendingCount(0);
        job.setStatus(BulkEmailJob.JobStatus.FAILED);
        job.setCompletedAt(LocalDateTime.now());
        addAuditEvent(job, BulkEmailJob.BulkEmailAuditEvent.EventType.JOB_FAILED, "Job failed: " + error);
        jobRepo.save(job);
    }

    private void addAuditEvent(BulkEmailJob job,
                               BulkEmailJob.BulkEmailAuditEvent.EventType type,
                               String description) {
        if (job.getAuditEvents() == null) job.setAuditEvents(new java.util.ArrayList<>());
        job.getAuditEvents().add(BulkEmailJob.BulkEmailAuditEvent.builder()
                .type(type)
                .description(description)
                .timestamp(LocalDateTime.now())
                .build());
    }

    private String substituteVars(String template, Map<String, String> data) {
        if (template == null) return "";
        String result = template;
        for (Map.Entry<String, String> e : data.entrySet()) {
            result = result.replace("{{" + e.getKey() + "}}", e.getValue() != null ? e.getValue() : "");
        }
        return result;
    }

    private byte[] fetchExternalPdf(BulkEmailJob job, BulkEmailJob.BulkEmailRow row) {
        String url    = substituteVars(job.getExternalApiUrl(), row.getData());
        String method = job.getExternalApiMethod() != null
                ? job.getExternalApiMethod().toUpperCase() : "GET";

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_PDF, MediaType.APPLICATION_OCTET_STREAM));

        if (job.getExternalApiHeaders() != null && !job.getExternalApiHeaders().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> hdrMap = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(job.getExternalApiHeaders(), Map.class);
                hdrMap.forEach(headers::set);
            } catch (Exception e) {
                log.warn("Could not parse externalApiHeaders for job {}: {}", job.getId(), e.getMessage());
            }
        }

        ResponseEntity<byte[]> resp;
        if ("POST".equals(method)) {
            String body = substituteVars(
                    job.getExternalApiBody() != null ? job.getExternalApiBody() : "{}", row.getData());
            headers.setContentType(MediaType.APPLICATION_JSON);
            resp = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), byte[].class);
        } else {
            resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        }

        if (resp.getBody() == null || resp.getBody().length == 0)
            throw new RuntimeException("External API returned empty response");
        return resp.getBody();
    }

    private byte[] generateDetailExcel(BulkEmailJob job, String idValue) {
        List<Map<String, String>> allDetailRows = job.getDetailSheetRows();
        List<String> columns = job.getDetailSheetColumns();

        List<Map<String, String>> matchingRows = (allDetailRows == null || job.getDetailSheetIdColumn() == null)
                ? List.of()
                : allDetailRows.stream()
                        .filter(r -> idValue.equalsIgnoreCase(
                                r.getOrDefault(job.getDetailSheetIdColumn(), "")))
                        .toList();

        if ((columns == null || columns.isEmpty()) && !matchingRows.isEmpty()) {
            columns = new java.util.ArrayList<>(matchingRows.get(0).keySet());
        }
        if (columns == null) columns = List.of();

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {

            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("Details");

            if (!columns.isEmpty()) {
                org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
                for (int i = 0; i < columns.size(); i++) {
                    header.createCell(i).setCellValue(columns.get(i));
                }
            }

            for (int ri = 0; ri < matchingRows.size(); ri++) {
                org.apache.poi.ss.usermodel.Row dataRow = sheet.createRow(ri + 1);
                Map<String, String> rowData = matchingRows.get(ri);
                for (int ci = 0; ci < columns.size(); ci++) {
                    dataRow.createCell(ci).setCellValue(rowData.getOrDefault(columns.get(ci), ""));
                }
            }

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            wb.write(baos);
            return baos.toByteArray();

        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to generate Excel attachment: " + e.getMessage(), e);
        }
    }

    private String sanitize(String name) {
        return name == null ? "document" : name.replaceAll("[^a-zA-Z0-9._\\- ]", "").trim();
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
