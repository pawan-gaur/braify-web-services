package com.braify.feature.bulkemail.dto;

import com.braify.feature.bulkemail.model.BulkEmailJob;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Data @Builder
public class BulkEmailJobResponse {

    private String id;
    private String label;
    private String createdBy;
    private String orgId;
    private String emailTemplateId;
    private String emailTemplateName;
    private String emailTemplateSubject;
    private BulkEmailJob.AttachmentType attachmentType;
    private String uploadedPdfName;
    private String pdfTemplateName;
    private String externalApiUrl;
    private String externalApiMethod;
    private BulkEmailJob.JobStatus status;
    private int totalCount;
    private int sentCount;
    private int failedCount;
    private int pendingCount;
    // Engagement (denormalised counters; analytics endpoint recomputes distinct counts exactly)
    private int totalOpens;
    private int totalClicks;
    private int openedCount;
    private int clickedCount;
    private int unsubscribedCount;
    private int suppressedCount;
    private int invalidSkippedCount;
    private int duplicateSkippedCount;
    private LocalDateTime scheduledAt;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private List<RowResponse>        rows;        // null when not requested
    private List<AuditEventResponse> auditEvents; // null when not requested

    @Data @Builder
    public static class RowResponse {
        private int    rowIndex;
        private String recipientEmail;
        private String recipientName;
        private BulkEmailJob.BulkEmailRow.RowStatus status;
        private String error;
        private String messageId;
        private LocalDateTime sentAt;
        // Engagement
        private int openCount;
        private LocalDateTime firstOpenedAt;
        private LocalDateTime lastOpenedAt;
        private int clickCount;
        private LocalDateTime firstClickedAt;
        private LocalDateTime lastClickedAt;
        private boolean unsubscribed;
        private LocalDateTime unsubscribedAt;
    }

    @Data @Builder
    public static class AuditEventResponse {
        private BulkEmailJob.BulkEmailAuditEvent.EventType type;
        private String        description;
        private LocalDateTime timestamp;
    }

    public static BulkEmailJobResponse from(BulkEmailJob job, boolean includeRows) {
        var b = BulkEmailJobResponse.builder()
                .id(job.getId())
                .label(job.getLabel())
                .createdBy(job.getCreatedBy())
                .orgId(job.getOrgId())
                .emailTemplateId(job.getEmailTemplateId())
                .emailTemplateName(job.getEmailTemplateName())
                .emailTemplateSubject(job.getEmailTemplateSubject())
                .attachmentType(job.getAttachmentType())
                .uploadedPdfName(job.getUploadedPdfName())
                .pdfTemplateName(job.getPdfTemplateName())
                .externalApiUrl(job.getExternalApiUrl())
                .externalApiMethod(job.getExternalApiMethod())
                .status(job.getStatus())
                .totalCount(job.getTotalCount())
                .sentCount(job.getSentCount())
                .failedCount(job.getFailedCount())
                .pendingCount(job.getPendingCount())
                .totalOpens(job.getTotalOpens())
                .totalClicks(job.getTotalClicks())
                .openedCount(job.getOpenedCount())
                .clickedCount(job.getClickedCount())
                .unsubscribedCount(job.getUnsubscribedCount())
                .suppressedCount(job.getSuppressedCount())
                .invalidSkippedCount(job.getInvalidSkippedCount())
                .duplicateSkippedCount(job.getDuplicateSkippedCount())
                .scheduledAt(job.getScheduledAt())
                .createdAt(job.getCreatedAt())
                .startedAt(job.getStartedAt())
                .completedAt(job.getCompletedAt());

        if (includeRows && job.getRows() != null) {
            b.rows(job.getRows().stream()
                    .map(r -> RowResponse.builder()
                            .rowIndex(r.getRowIndex())
                            .recipientEmail(r.getRecipientEmail())
                            .recipientName(r.getRecipientName())
                            .status(r.getStatus())
                            .error(r.getError())
                            .messageId(r.getMessageId())
                            .sentAt(r.getSentAt())
                            .openCount(r.getOpenCount())
                            .firstOpenedAt(r.getFirstOpenedAt())
                            .lastOpenedAt(r.getLastOpenedAt())
                            .clickCount(r.getClickCount())
                            .firstClickedAt(r.getFirstClickedAt())
                            .lastClickedAt(r.getLastClickedAt())
                            .unsubscribed(r.isUnsubscribed())
                            .unsubscribedAt(r.getUnsubscribedAt())
                            .build())
                    .collect(Collectors.toList()));
        }

        if (job.getAuditEvents() != null) {
            b.auditEvents(job.getAuditEvents().stream()
                    .map(e -> AuditEventResponse.builder()
                            .type(e.getType())
                            .description(e.getDescription())
                            .timestamp(e.getTimestamp())
                            .build())
                    .collect(Collectors.toList()));
        }

        return b.build();
    }
}
