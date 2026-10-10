package com.braify.feature.bulkemail.service;

import com.braify.config.infra.email.EmailDispatcher;
import com.braify.feature.bulkemail.migration.BulkEmailRowMigrationService;
import com.braify.feature.bulkemail.model.BulkEmailDetailRow;
import com.braify.feature.bulkemail.model.BulkEmailJob;
import com.braify.feature.bulkemail.model.BulkEmailJob.BulkEmailAuditEvent.EventType;
import com.braify.feature.bulkemail.model.BulkEmailJob.JobStatus;
import com.braify.feature.bulkemail.model.BulkEmailRecipient;
import com.braify.feature.bulkemail.repository.BulkEmailJobRepository;
import com.braify.feature.emaillog.model.EmailLog;
import com.braify.feature.emaillog.service.EmailLogService;
import com.braify.feature.pdf.model.Template;
import com.braify.feature.pdf.repository.TemplateRepository;
import com.braify.feature.pdf.service.PdfGenerationService;
import com.braify.feature.placeholder.service.GlobalPlaceholderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends a bulk-email campaign. Owns the {@code @Async} entry point (a separate bean from
 * {@link BulkEmailService} so the async proxy is honoured).
 *
 * <h3>Design (memory stays flat at any campaign size)</h3>
 * <ul>
 *   <li>Recipients are read from {@code bulk_email_recipients} in keyset pages of
 *       {@code bulkemail.batch-size}; the campaign is never loaded into memory as a whole.</li>
 *   <li>Each page is sent by a bounded pool of {@code bulkemail.concurrency} workers, paced by
 *       the shared {@link EmailRateLimiter}.</li>
 *   <li>Each result is persisted with a small targeted update on that recipient; the job's
 *       counters are re-derived from the recipients ({@link BulkEmailJobStore#refreshCounters}),
 *       so there is no full-document rewrite and nothing to drift.</li>
 *   <li>Cancellation is honoured between pages and between rows.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BulkEmailProcessor {

    /** Re-derive counters / re-check cancellation every N recipients. */
    private static final int  PROGRESS_EVERY  = 25;
    /** Extra passes for recipients still PENDING after a pass (e.g. a transient DB write failure). */
    private static final int  MAX_PASSES      = 3;
    private static final long BASE_BACKOFF_MS = 500;

    // ── Dependencies ──────────────────────────────────────────────────────────
    private final BulkEmailJobRepository       jobRepo;
    private final BulkEmailJobStore            jobStore;
    private final BulkEmailRecipientStore      recipientStore;
    private final BulkEmailRowMigrationService migrationService;
    private final TemplateRepository           pdfTemplateRepo;
    private final EmailDispatcher              emailDispatcher;
    private final EmailLogService              emailLogService;
    private final EmailTrackingService         emailTrackingService;
    private final EmailRateLimiter             rateLimiter;
    private final PdfGenerationService         pdfGenerationService;
    private final GlobalPlaceholderService     globalPlaceholderService;

    /** Parallel workers per campaign. Keep low on small instances (e.g. 2–3 on a t3.micro). */
    @Value("${bulkemail.concurrency:3}")
    private int concurrency;

    /** Recipients fetched per page. */
    @Value("${bulkemail.batch-size:100}")
    private int batchSize;

    /** Max send attempts per recipient (transient failures only). */
    @Value("${bulkemail.max-send-attempts:3}")
    private int maxSendAttempts;

    /** Shared RestTemplate — thread-safe; reused across all jobs. */
    private final RestTemplate restTemplate = new RestTemplate();

    /** Campaigns being sent in this JVM — a second start of the same job is ignored. */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    // ── Entry point ───────────────────────────────────────────────────────────

    @Async
    public void processJobAsync(String jobId) {
        if (!running.add(jobId)) {
            log.info("BulkEmailJob '{}' is already being processed — ignoring duplicate start", jobId);
            return;
        }
        try {
            process(jobId);
        } catch (Exception e) {
            log.error("BulkEmailJob '{}' processing aborted: {}", jobId, e.getMessage(), e);
        } finally {
            running.remove(jobId);
        }
    }

    private void process(String jobId) throws InterruptedException {
        migrationService.ensureMigrated(jobId);   // legacy jobs: move embedded rows out first

        // Claim the job. CANCELLED / finished jobs are never (re)started here.
        if (!jobStore.transition(jobId, List.of(JobStatus.PENDING, JobStatus.PROCESSING), JobStatus.PROCESSING, null)) {
            log.info("BulkEmailJob '{}' not runnable (status={}) — skipping", jobId, jobStore.status(jobId));
            return;
        }
        BulkEmailJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null) return;
        if (job.getStartedAt() == null) {
            jobStore.transition(jobId, List.of(JobStatus.PROCESSING), JobStatus.PROCESSING,
                    new Update().set("startedAt", LocalDateTime.now()));
        }
        jobStore.pushAudit(jobId, EventType.PROCESSING_STARTED, "Email dispatch started");

        // Pre-load the PDF template once (shared read-only across workers).
        Template pdfTemplate = null;
        if (job.getAttachmentType() == BulkEmailJob.AttachmentType.PDF_TEMPLATE) {
            pdfTemplate = pdfTemplateRepo.findById(job.getPdfTemplateId()).orElse(null);
            if (pdfTemplate == null) {
                log.error("PDF template {} not found — failing job {}", job.getPdfTemplateId(), jobId);
                recipientStore.failAllPending(jobId, "PDF template not found");
                finish(jobId, false);
                return;
            }
        }

        final Template            finalPdfTemplate = pdfTemplate;
        final Map<String, Object> jobGlobals       = globalPlaceholderService.resolveForOrg(job.getOrgId());
        final AtomicBoolean       cancelled        = new AtomicBoolean(false);
        final AtomicInteger       done             = new AtomicInteger();
        int workers = Math.max(1, concurrency);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        log.info("BulkEmailJob '{}' started: {} worker(s), batch {}", jobId, workers, batchSize);

        try {
            for (int pass = 1; pass <= MAX_PASSES && !cancelled.get(); pass++) {
                int cursor = -1;
                boolean foundAny = false;
                while (!cancelled.get()) {
                    if (jobStore.status(jobId) == JobStatus.CANCELLED) { cancelled.set(true); break; }

                    List<BulkEmailRecipient> page = recipientStore.nextPending(jobId, cursor, batchSize);
                    if (page.isEmpty()) break;
                    foundAny = true;
                    cursor = page.get(page.size() - 1).getSeq();

                    List<Future<?>> futures = new ArrayList<>(page.size());
                    for (BulkEmailRecipient r : page) {
                        futures.add(pool.submit(() ->
                                sendOne(job, r, finalPdfTemplate, jobGlobals, cancelled, done)));
                    }
                    for (Future<?> f : futures) {
                        try {
                            f.get();
                        } catch (ExecutionException e) {
                            log.warn("Worker error in job {}: {}", jobId, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                        }
                    }
                    jobStore.refreshCounters(jobId);
                }
                if (!foundAny) break;   // nothing pending — done
            }
        } catch (InterruptedException ie) {
            // Shutting down: leave the job PROCESSING so it resumes on the next start.
            Thread.currentThread().interrupt();
            log.warn("BulkEmailJob '{}' interrupted — will resume on next start", jobId);
            throw ie;
        } finally {
            pool.shutdownNow();
        }

        finish(jobId, cancelled.get());
    }

    /** Records the final status from the recipients' actual outcomes. */
    private void finish(String jobId, boolean cancelled) {
        BulkEmailJobStore.Counts c = jobStore.refreshCounters(jobId);
        if (cancelled) {
            log.info("BulkEmailJob '{}' stopped by cancellation: sent={} failed={} pending={}",
                    jobId, c.sent(), c.failed(), c.pending());
            return;   // status is already CANCELLED; pending recipients remain resumable
        }
        if (c.pending() > 0) {
            // Still pending after every pass — fail them visibly so "Resend failed" can retry them.
            recipientStore.failAllPending(jobId, "Not processed — use Resend failed to retry");
            c = jobStore.refreshCounters(jobId);
        }

        JobStatus finalStatus;
        EventType event;
        String    description;
        if (c.failed() == 0) {
            finalStatus = JobStatus.COMPLETED; event = EventType.JOB_COMPLETED;
            description = "All " + c.sent() + " email(s) delivered successfully";
        } else if (c.sent() == 0) {
            finalStatus = JobStatus.FAILED; event = EventType.JOB_FAILED;
            description = "All " + c.failed() + " recipient(s) failed to send";
        } else {
            finalStatus = JobStatus.PARTIAL; event = EventType.JOB_PARTIAL;
            description = c.sent() + " sent, " + c.failed() + " failed out of " + c.total();
        }
        // Conditional: a cancel that raced the last page must not be overwritten.
        if (jobStore.transition(jobId, List.of(JobStatus.PROCESSING), finalStatus,
                new Update().set("completedAt", LocalDateTime.now()))) {
            jobStore.pushAudit(jobId, event, description);
        }
        log.info("BulkEmailJob '{}' done: sent={} failed={} status={}", jobId, c.sent(), c.failed(), finalStatus);
    }

    // ── One recipient (runs on a pool worker) ─────────────────────────────────

    private void sendOne(BulkEmailJob job,
                         BulkEmailRecipient r,
                         Template pdfTemplate,
                         Map<String, Object> jobGlobals,
                         AtomicBoolean cancelled,
                         AtomicInteger done) {

        // Leave the recipient PENDING if the campaign was cancelled — "Retry pending" resumes it.
        if (cancelled.get() || Thread.currentThread().isInterrupted()) return;

        Map<String, String> data = r.getData() != null ? r.getData() : Map.of();
        List<String> ccRecipients = null;
        String messageId = null;
        String error     = null;

        try {
            // 1. Placeholders — org globals first, then per-row column values / recipient fields.
            Map<String, Object> emailPlaceholders = new HashMap<>(jobGlobals);
            if (job.getColumnMapping() != null) {
                job.getColumnMapping().forEach((placeholder, xlsxCol) ->
                        emailPlaceholders.put(placeholder, data.getOrDefault(xlsxCol, "")));
            }
            emailPlaceholders.put("email", r.getRecipientEmail());
            if (r.getRecipientName() != null && !r.getRecipientName().isBlank())
                emailPlaceholders.put("name", r.getRecipientName());

            // 2. Subject
            String subject = substituteVars(job.getEmailTemplateSubject(), data);

            // 3. Attachments — primary type + optional secondary Excel sheet, in order.
            Map<String, byte[]> attachments = new LinkedHashMap<>();
            switch (job.getAttachmentType()) {
                case UPLOAD -> {
                    String name = job.getUploadedPdfName() != null ? job.getUploadedPdfName() : "attachment.pdf";
                    if (job.getUploadedPdfData() != null) attachments.put(name, job.getUploadedPdfData());
                }
                case PDF_TEMPLATE -> {
                    Map<String, Object> pdfData = new HashMap<>();
                    if (job.getPdfColumnMapping() != null) {
                        job.getPdfColumnMapping().forEach((pdfVar, xlsxCol) ->
                                pdfData.put(pdfVar, data.getOrDefault(xlsxCol, "")));
                    } else {
                        pdfData.putAll(data);
                    }
                    String pdfName = sanitize(job.getPdfTemplateName() != null
                            ? job.getPdfTemplateName() : "document") + ".pdf";
                    attachments.put(pdfName, pdfGenerationService.generate(pdfTemplate, pdfData));
                }
                case EXTERNAL_API -> attachments.put("attachment.pdf", fetchExternalPdf(job, data));
                case EXCEL_SHEET -> attachments.put(detailFileName(job, data), generateDetailExcel(job, data));
                default -> { /* NONE — no primary attachment */ }
            }
            if (job.isIncludeExcelSheet()
                    && job.getAttachmentType() != BulkEmailJob.AttachmentType.EXCEL_SHEET
                    && job.getDetailSheetIdColumn() != null && job.getMainSheetIdColumn() != null) {
                attachments.put(detailFileName(job, data), generateDetailExcel(job, data));
            }

            // 4. CC recipients from configured columns
            if (job.getCcColumns() != null && !job.getCcColumns().isEmpty()) {
                ccRecipients = job.getCcColumns().stream()
                        .map(col -> data.getOrDefault(col, "").trim())
                        .filter(addr -> !addr.isBlank() && addr.contains("@"))
                        .distinct()
                        .toList();
                if (ccRecipients.isEmpty()) ccRecipients = null;
            }

            // 5. Send — open pixel / tracked links / unsubscribe injected on the final HTML.
            String senderName = (job.getOrgName() != null && !job.getOrgName().isBlank())
                    ? job.getOrgName() : job.getEmailTemplateName();
            String trackingId = r.getTrackingId();
            java.util.function.UnaryOperator<String> tracking =
                    (trackingId != null && !trackingId.isBlank())
                            ? html -> emailTrackingService.applyTracking(html, trackingId)
                            : null;

            final List<String> ccFinal = ccRecipients;
            Callable<com.resend.services.emails.model.CreateEmailResponse> sendOnce =
                    () -> attachments.isEmpty()
                        ? emailDispatcher.sendHtmlEmail(
                                r.getRecipientEmail(), ccFinal, subject,
                                job.getEmailTemplateHtml(), emailPlaceholders, senderName, tracking)
                        : emailDispatcher.sendHtmlEmailWithAttachments(
                                r.getRecipientEmail(), ccFinal, subject,
                                job.getEmailTemplateHtml(), emailPlaceholders,
                                attachments, senderName, tracking);

            var response = sendWithRetry(sendOnce, r, job.getId());
            messageId = response != null ? response.getId() : null;
            recipientStore.markSent(r.getId(), messageId);

        } catch (Exception ex) {
            log.warn("Recipient {} failed in job {}: {}", r.getSeq(), job.getId(), ex.getMessage());
            error = truncate(ex.getMessage(), 200);
            try {
                recipientStore.markFailed(r.getId(), error);
            } catch (Exception dbEx) {
                // Left PENDING — the next pass retries it.
                log.warn("Could not record failure for recipient {} in job {}: {}", r.getSeq(), job.getId(), dbEx.getMessage());
                return;
            }
        }

        recordEmailLog(job, r, ccRecipients, messageId, error);

        // Progress + cancellation check every N recipients.
        if (done.incrementAndGet() % PROGRESS_EVERY == 0) {
            try {
                jobStore.refreshCounters(job.getId());
                if (jobStore.status(job.getId()) == JobStatus.CANCELLED) cancelled.set(true);
            } catch (Exception e) {
                log.debug("Progress refresh failed for job {}: {}", job.getId(), e.getMessage());
            }
        }
    }

    /** Email audit log: the To recipient plus one entry per CC recipient. */
    private void recordEmailLog(BulkEmailJob job, BulkEmailRecipient r, List<String> cc,
                                String messageId, String error) {
        EmailLog.Status status = error == null ? EmailLog.Status.SENT : EmailLog.Status.FAILED;
        emailLogService.record(EmailLog.builder()
                .orgId(job.getOrgId())
                .category(EmailLog.Category.BULK_EMAIL)
                .status(status)
                .recipient(r.getRecipientEmail())
                .ccEmails(cc)
                .subject(job.getEmailTemplateSubject())
                .senderName(job.getOrgName())
                .providerMessageId(messageId)
                .errorMessage(error)
                .relatedType(EmailLog.RelatedType.BULK_EMAIL_JOB)
                .relatedId(job.getId())
                .createdBy(job.getCreatedBy())
                .build());
        if (cc != null) {
            for (String ccAddr : cc) {
                emailLogService.record(EmailLog.builder()
                        .orgId(job.getOrgId())
                        .category(EmailLog.Category.BULK_EMAIL)
                        .status(status)
                        .recipient(ccAddr)
                        .cc(true)
                        .subject(job.getEmailTemplateSubject())
                        .senderName(job.getOrgName())
                        .providerMessageId(messageId)
                        .errorMessage(error)
                        .relatedType(EmailLog.RelatedType.BULK_EMAIL_JOB)
                        .relatedId(job.getId())
                        .createdBy(job.getCreatedBy())
                        .build());
            }
        }
    }

    // ── Sending with retry ────────────────────────────────────────────────────

    /**
     * Sends one email, respecting the shared rate limit, and retries transient failures
     * (429 / 5xx / timeouts / connection resets) with exponential backoff + jitter.
     * Permanent failures (e.g. a rejected address) fail fast without retry.
     */
    private com.resend.services.emails.model.CreateEmailResponse sendWithRetry(
            Callable<com.resend.services.emails.model.CreateEmailResponse> send,
            BulkEmailRecipient r, String jobId) throws Exception {

        int attempts = Math.max(1, maxSendAttempts);
        Exception last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            rateLimiter.acquire();
            try {
                return send.call();
            } catch (Exception e) {
                last = e;
                if (attempt >= attempts || !isTransient(e)) throw e;
                long backoff = (long) (BASE_BACKOFF_MS * Math.pow(2, attempt - 1))
                        + ThreadLocalRandom.current().nextLong(250);
                log.warn("Transient send failure recipient {} job {} (attempt {}/{}): {} — retrying in {}ms",
                        r.getSeq(), jobId, attempt, attempts, e.getMessage(), backoff);
                Thread.sleep(backoff);
            }
        }
        throw last;   // unreachable — loop either returns or throws
    }

    /** Heuristic: is this failure worth retrying, or is it a permanent rejection? */
    private static boolean isTransient(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.io.IOException) return true;
            String m = c.getMessage();
            if (m != null) {
                String s = m.toLowerCase();
                if (s.contains("429") || s.contains("rate limit") || s.contains("too many")
                        || s.contains("timeout") || s.contains("timed out") || s.contains("temporarily")
                        || s.contains("500") || s.contains("502") || s.contains("503") || s.contains("504")
                        || s.contains("connection reset") || s.contains("connection refused")
                        || s.contains("unavailable"))
                    return true;
            }
        }
        return false;
    }

    // ── Attachments ───────────────────────────────────────────────────────────

    private byte[] fetchExternalPdf(BulkEmailJob job, Map<String, String> data) {
        String url    = substituteVars(job.getExternalApiUrl(), data);
        String method = job.getExternalApiMethod() != null ? job.getExternalApiMethod().toUpperCase() : "GET";

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
            String body = substituteVars(job.getExternalApiBody() != null ? job.getExternalApiBody() : "{}", data);
            headers.setContentType(MediaType.APPLICATION_JSON);
            resp = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), byte[].class);
        } else {
            resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        }

        if (resp.getBody() == null || resp.getBody().length == 0)
            throw new RuntimeException("External API returned empty response");
        return resp.getBody();
    }

    private String detailFileName(BulkEmailJob job, Map<String, String> data) {
        String tpl = (job.getDetailSheetFileName() != null && !job.getDetailSheetFileName().isBlank())
                ? job.getDetailSheetFileName()
                : "details_{{" + job.getMainSheetIdColumn() + "}}.xlsx";
        return substituteVars(tpl, data);
    }

    /** Per-recipient Excel built from the Sheet-2 rows matching this recipient (indexed lookup). */
    private byte[] generateDetailExcel(BulkEmailJob job, Map<String, String> data) {
        String idValue = data.getOrDefault(job.getMainSheetIdColumn(), "");
        List<Map<String, String>> matchingRows = job.getDetailSheetIdColumn() == null
                ? List.of()
                : recipientStore.detailRowsFor(job.getId(), idValue).stream()
                        .map(BulkEmailDetailRow::getData)
                        .map(m -> m != null ? m : Map.<String, String>of())
                        .toList();

        List<String> columns = job.getDetailSheetColumns();
        if ((columns == null || columns.isEmpty()) && !matchingRows.isEmpty()) {
            columns = new ArrayList<>(matchingRows.get(0).keySet());
        }
        if (columns == null) columns = List.of();

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("Details");
            if (!columns.isEmpty()) {
                org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
                for (int i = 0; i < columns.size(); i++) header.createCell(i).setCellValue(columns.get(i));
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String substituteVars(String template, Map<String, String> data) {
        if (template == null) return "";
        String result = template;
        for (Map.Entry<String, String> e : data.entrySet()) {
            result = result.replace("{{" + e.getKey() + "}}", e.getValue() != null ? e.getValue() : "");
        }
        return result;
    }

    private String sanitize(String name) {
        return name == null ? "document" : name.replaceAll("[^a-zA-Z0-9._\\- ]", "").trim();
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
