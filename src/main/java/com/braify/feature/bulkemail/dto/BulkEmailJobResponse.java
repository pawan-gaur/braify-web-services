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
