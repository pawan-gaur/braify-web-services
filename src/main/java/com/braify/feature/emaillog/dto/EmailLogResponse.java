package com.braify.feature.emaillog.dto;

import com.braify.feature.emaillog.model.EmailLog;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** Read model for the Email Activity viewer. */
@Data @Builder
public class EmailLogResponse {
    private String        id;
    private String        orgId;
    private String        orgName;        // resolved for display (platform view spans orgs)
    private String        category;
    private String        status;
    private String        recipient;
    private boolean       cc;             // true = this row tracks a CC recipient of the send
    private List<String>  ccEmails;
    private String        subject;
    private String        senderName;
    private String        providerMessageId;
    private String        errorMessage;
    private String        relatedType;
    private String        relatedId;
    private String        createdBy;
    private LocalDateTime createdAt;

    public static EmailLogResponse from(EmailLog e, String orgName) {
        return EmailLogResponse.builder()
                .id(e.getId())
                .orgId(e.getOrgId())
                .orgName(orgName)
                .category(e.getCategory() != null ? e.getCategory().name() : null)
                .status(e.getStatus() != null ? e.getStatus().name() : null)
                .recipient(e.getRecipient())
                .cc(e.isCc())
                .ccEmails(e.getCcEmails())
                .subject(e.getSubject())
                .senderName(e.getSenderName())
                .providerMessageId(e.getProviderMessageId())
                .errorMessage(e.getErrorMessage())
                .relatedType(e.getRelatedType() != null ? e.getRelatedType().name() : null)
                .relatedId(e.getRelatedId())
                .createdBy(e.getCreatedBy())
                .createdAt(e.getCreatedAt())
                .build();
    }
}
