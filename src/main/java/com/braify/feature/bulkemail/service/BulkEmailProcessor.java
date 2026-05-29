package com.braify.feature.bulkemail.service;

import com.braify.config.infra.email.EmailDispatcher;
import com.braify.feature.bulkemail.model.BulkEmailJob;
import com.braify.feature.bulkemail.repository.BulkEmailJobRepository;
import com.braify.feature.pdf.model.Template;
import com.braify.feature.pdf.repository.TemplateRepository;
import com.braify.feature.pdf.service.PdfGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Separate Spring bean that owns the {@code @Async} bulk-email processing method.
 *
 * <p>Spring's {@code @Async} proxy only intercepts calls that cross a bean boundary.
 * Calling {@code this.processJobAsync()} inside {@link BulkEmailService} would bypass
 * the proxy and run synchronously, blocking the HTTP response thread until every email
 * is sent.  By moving the method here, {@code BulkEmailService} calls it through the
 * proxy and the method executes on Spring's async executor thread pool.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BulkEmailProcessor {

    private static final int SEND_DELAY_MS = 150;  // throttle to respect Resend rate limits

    private final BulkEmailJobRepository jobRepo;
    private final TemplateRepository     pdfTemplateRepo;
    private final EmailDispatcher        emailDispatcher;
    private final PdfGenerationService   pdfGenerationService;

    // ── Entry point ──────────────────────────────────────────────────────────

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

        // Pre-load PDF template if needed
        Template pdfTemplate = null;
        if (job.getAttachmentType() == BulkEmailJob.AttachmentType.PDF_TEMPLATE) {
            pdfTemplate = pdfTemplateRepo.findById(job.getPdfTemplateId()).orElse(null);
            if (pdfTemplate == null) {
                log.error("PDF template {} not found — failing job {}", job.getPdfTemplateId(), jobId);
                markAllFailed(job, "PDF template not found");
                return;
            }
        }

        RestTemplate restTemplate = job.getAttachmentType() == BulkEmailJob.AttachmentType.EXTERNAL_API
                ? new RestTemplate() : null;

        int sentCount   = 0;
        int failedCount = 0;

        for (BulkEmailJob.BulkEmailRow row : job.getRows()) {
            if (row.getStatus() != BulkEmailJob.BulkEmailRow.RowStatus.PENDING) {
                if (row.getStatus() == BulkEmailJob.BulkEmailRow.RowStatus.SENT) sentCount++;
                else failedCount++;
                continue;
            }

            try {
                // 1. Build email placeholder map from column mapping + row data
                Map<String, Object> emailPlaceholders = new HashMap<>();
                if (job.getColumnMapping() != null) {
                    job.getColumnMapping().forEach((placeholder, xlsxCol) ->
                            emailPlaceholders.put(placeholder, row.getData().getOrDefault(xlsxCol, "")));
                }
                emailPlaceholders.put("email", row.getRecipientEmail());
                if (row.getRecipientName() != null && !row.getRecipientName().isBlank())
                    emailPlaceholders.put("name", row.getRecipientName());

                // 2. Resolve subject (may also contain {{placeholders}})
                String subject = substituteVars(job.getEmailTemplateSubject(), row.getData());

                // 3. Build attachment (if any)
                byte[] attachmentData     = null;
                String attachmentFileName = null;

                switch (job.getAttachmentType()) {
                    case UPLOAD -> {
                        attachmentData     = job.getUploadedPdfData();
                        attachmentFileName = job.getUploadedPdfName() != null
                                ? job.getUploadedPdfName() : "attachment.pdf";
                    }
                    case PDF_TEMPLATE -> {
                        Map<String, Object> pdfData = new HashMap<>();
                        if (job.getPdfColumnMapping() != null) {
                            job.getPdfColumnMapping().forEach((pdfVar, xlsxCol) ->
                                    pdfData.put(pdfVar, row.getData().getOrDefault(xlsxCol, "")));
                        } else {
                            pdfData.putAll(row.getData());
                        }
                        attachmentData = pdfGenerationService.generate(pdfTemplate, pdfData);
                        attachmentFileName = sanitize(job.getPdfTemplateName() != null
                                ? job.getPdfTemplateName() : "document") + ".pdf";
                    }
                    case EXTERNAL_API -> {
                        attachmentData     = fetchExternalPdf(restTemplate, job, row);
                        attachmentFileName = "attachment.pdf";
                    }
                    case EXCEL_SHEET -> {
                        String idValue = row.getData().getOrDefault(job.getMainSheetIdColumn(), "");
                        attachmentData = generateDetailExcel(job, idValue);
                        String tpl = (job.getDetailSheetFileName() != null && !job.getDetailSheetFileName().isBlank())
                                ? job.getDetailSheetFileName()
                                : "details_{{" + job.getMainSheetIdColumn() + "}}.xlsx";
                        attachmentFileName = substituteVars(tpl, row.getData());
                    }
                    default -> { /* NONE — no attachment */ }
                }

                // 4. Send  (use org name as the "From:" display name)
                String senderName = (job.getOrgName() != null && !job.getOrgName().isBlank())
                        ? job.getOrgName()
                        : job.getEmailTemplateName();  // graceful fallback for legacy jobs

                var response = (attachmentData != null)
                        ? emailDispatcher.sendHtmlEmailWithAttachment(
                                row.getRecipientEmail(), subject,
                                job.getEmailTemplateHtml(), emailPlaceholders,
                                attachmentData, attachmentFileName,
                                senderName)
                        : emailDispatcher.sendHtmlEmail(
                                row.getRecipientEmail(), subject,
                                job.getEmailTemplateHtml(), emailPlaceholders,
                                senderName);

                row.setStatus(BulkEmailJob.BulkEmailRow.RowStatus.SENT);
                row.setSentAt(LocalDateTime.now());
                row.setMessageId(response != null ? response.getId() : null);
                sentCount++;

            } catch (Exception ex) {
                log.warn("Row {} failed in job {}: {}", row.getRowIndex(), jobId, ex.getMessage());
                row.setStatus(BulkEmailJob.BulkEmailRow.RowStatus.FAILED);
                row.setError(truncate(ex.getMessage(), 200));
                failedCount++;
            }

            // Persist incremental progress after every row for live status polling
            job.setSentCount(sentCount);
            job.setFailedCount(failedCount);
            job.setPendingCount(job.getTotalCount() - sentCount - failedCount);
            jobRepo.save(job);

            if (Thread.currentThread().isInterrupted()) break;
            try { Thread.sleep(SEND_DELAY_MS); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Final state
        job.setCompletedAt(LocalDateTime.now());
        job.setPendingCount(0);
        if (failedCount == 0) {
            job.setStatus(BulkEmailJob.JobStatus.COMPLETED);
            addAuditEvent(job, BulkEmailJob.BulkEmailAuditEvent.EventType.JOB_COMPLETED,
                    "All " + sentCount + " email(s) delivered successfully");
        } else if (sentCount == 0) {
            job.setStatus(BulkEmailJob.JobStatus.FAILED);
            addAuditEvent(job, BulkEmailJob.BulkEmailAuditEvent.EventType.JOB_FAILED,
                    "All " + failedCount + " recipient(s) failed to send");
        } else {
            job.setStatus(BulkEmailJob.JobStatus.PARTIAL);
            addAuditEvent(job, BulkEmailJob.BulkEmailAuditEvent.EventType.JOB_PARTIAL,
                    sentCount + " sent, " + failedCount + " failed out of " + job.getTotalCount());
        }
        jobRepo.save(job);
        log.info("BulkEmailJob '{}' done: sent={} failed={} status={}", jobId, sentCount, failedCount, job.getStatus());
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

    /** Substitutes {{key}} placeholders from the given data map. */
    private String substituteVars(String template, Map<String, String> data) {
        if (template == null) return "";
        String result = template;
        for (Map.Entry<String, String> e : data.entrySet()) {
            result = result.replace("{{" + e.getKey() + "}}", e.getValue() != null ? e.getValue() : "");
        }
        return result;
    }

    /** Fetches binary content (PDF) from an external HTTP endpoint for a given row. */
    private byte[] fetchExternalPdf(RestTemplate rest, BulkEmailJob job, BulkEmailJob.BulkEmailRow row) {
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
            resp = rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), byte[].class);
        } else {
            resp = rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        }

        if (resp.getBody() == null || resp.getBody().length == 0)
            throw new RuntimeException("External API returned empty response");
        return resp.getBody();
    }

    /**
     * Generates an .xlsx file containing the Sheet 2 rows whose
     * {@code detailSheetIdColumn} value matches the given {@code idValue}.
     * Always produces a valid workbook — if no rows match, the file will
     * contain only the header row.
     */
    private byte[] generateDetailExcel(BulkEmailJob job, String idValue) {
        List<Map<String, String>> allDetailRows = job.getDetailSheetRows();
        List<String> columns = job.getDetailSheetColumns();

        // Filter rows for this recipient
        List<Map<String, String>> matchingRows = (allDetailRows == null || job.getDetailSheetIdColumn() == null)
                ? List.of()
                : allDetailRows.stream()
                        .filter(r -> idValue.equalsIgnoreCase(
                                r.getOrDefault(job.getDetailSheetIdColumn(), "")))
                        .toList();

        // Fall back to keys from first matched row if no explicit column list
        if ((columns == null || columns.isEmpty()) && !matchingRows.isEmpty()) {
            columns = new java.util.ArrayList<>(matchingRows.get(0).keySet());
        }
        if (columns == null) columns = List.of();

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {

            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("Details");

            // Header row
            if (!columns.isEmpty()) {
                org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
                for (int i = 0; i < columns.size(); i++) {
                    header.createCell(i).setCellValue(columns.get(i));
                }
            }

            // Data rows
            for (int ri = 0; ri < matchingRows.size(); ri++) {
                org.apache.poi.ss.usermodel.Row dataRow = sheet.createRow(ri + 1);
                Map<String, String> rowData = matchingRows.get(ri);
                for (int ci = 0; ci < columns.size(); ci++) {
                    dataRow.createCell(ci).setCellValue(
                            rowData.getOrDefault(columns.get(ci), ""));
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
